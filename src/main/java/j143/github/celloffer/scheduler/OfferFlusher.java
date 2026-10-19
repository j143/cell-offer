package j143.github.celloffer.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import j143.github.celloffer.model.Offer;
import j143.github.celloffer.push.OfferPushClient;
import j143.github.celloffer.queue.CellOfferQueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodic flush scheduler that drains each cell queue and forwards live offers
 * to the PushGateway via {@link OfferPushClient}.
 *
 * <p>The interval is configurable via {@code cell.offer.flush-interval-ms}.
 */
@Component
public class OfferFlusher {

    private static final Logger log = LoggerFactory.getLogger(OfferFlusher.class);

    private final CellOfferQueueManager queueManager;
    private final OfferPushClient       pushClient;
    private final Timer                 flushCycleTimer;
    private final BoundedOfferDispatcher dispatcher;

    public OfferFlusher(
            CellOfferQueueManager queueManager,
            OfferPushClient pushClient,
            MeterRegistry meterRegistry,
            BoundedOfferDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.queueManager = queueManager;
        this.pushClient   = pushClient;
        this.flushCycleTimer = Timer.builder("cell_offer_flush_cycle_duration")
                .description("Duration of one complete cell-offer flush cycle")
                .register(meterRegistry);
    }

    /**
     * Fires every {@code cell.offer.flush-interval-ms} milliseconds (default 300 ms).
     *
     * <p>For every registered cell it:
     * <ol>
     *   <li>Reserves bounded dispatch capacity before selecting live offers fairly.</li>
     *   <li>Hands ownership to workers; the scheduler never waits on PushGateway.</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${cell.offer.flush-interval-ms:300}")
    public void flush() {
        flushCycleTimer.record(this::flushOnce);
    }

    private void flushOnce() {
        dispatcher.tick();
    }

    /**
     * Forwards a flushed offer to PushGateway for delivery to the driver.
     * Falls back to logging if the push client encounters an error (handled inside
     * {@link OfferPushClient#sendOffer}).
     */
    private void sendToRamenSink(Offer offer) {
        String offerJson = String.format(
                "{\"offerId\":\"%s\",\"cellId\":\"%s\",\"driverId\":\"%s\","
                        + "\"riderId\":\"%s\",\"priority\":%d}",
                offer.getOfferId(), offer.getCellId(), offer.getDriverId(),
                offer.getRiderId(), offer.getPriority());

        pushClient.sendOffer(
                offer.getDriverId(),
                offerJson,
                offer.getTtl().toMillis(),
                offer.getPriority());
    }
}
