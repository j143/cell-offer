package j143.github.celloffer.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import j143.github.celloffer.push.OfferPushClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic flush scheduler that selects live offers and delegates delivery
 * to workers using the PushGateway via {@link OfferPushClient}.
 *
 * <p>The interval is configurable via {@code cell.offer.flush-interval-ms}.
 * Worker completions also replenish capacity; this timer is the idle/retry fallback.
 */
@Component
public class OfferFlusher {
    private final BoundedOfferDispatcher dispatcher;
    private final Timer flushCycleTimer;

    public OfferFlusher(BoundedOfferDispatcher dispatcher, MeterRegistry meterRegistry) {
        this.dispatcher = dispatcher;
        this.flushCycleTimer = Timer.builder("cell_offer_flush_cycle_duration")
                .description("Duration of a periodic offer selection pass, excluding worker RPC time")
                .register(meterRegistry);
    }

    /**
     * Fires every {@code cell.offer.flush-interval-ms} milliseconds (default 300 ms).
     *
     * <p>For every active cell it:
     * <ol>
     *   <li>Reserves bounded dispatch capacity before selecting live offers fairly.</li>
     *   <li>Hands ownership to workers; the scheduler never waits on PushGateway.</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${cell.offer.flush-interval-ms:300}")
    public void flush() {
        flushCycleTimer.record(dispatcher::tick);
    }
}
