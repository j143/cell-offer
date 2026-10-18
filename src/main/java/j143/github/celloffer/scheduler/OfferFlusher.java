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

@Component
public class OfferFlusher {

    private static final Logger log = LoggerFactory.getLogger(OfferFlusher.class);

    private final CellOfferQueueManager queueManager;
    private final OfferPushClient pushClient;
    private final Timer flushCycleTimer;

    public OfferFlusher(
            CellOfferQueueManager queueManager,
            OfferPushClient pushClient,
            MeterRegistry meterRegistry) {
        this.queueManager = queueManager;
        this.pushClient = pushClient;
        this.flushCycleTimer = Timer.builder("cell_offer_flush_cycle_duration")
                .description("Duration of one complete cell-offer flush cycle")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${cell.offer.flush-interval-ms:300}")
    public void flush() {
        flushCycleTimer.record(this::flushOnce);
    }

    private void flushOnce() {
        Instant now = Instant.now();
        for (String cellId : queueManager.getCellIds()) {
            List<Offer> offers = queueManager.flushDueOffers(cellId, now);
            if (!offers.isEmpty()) {
                log.info("[FLUSH] cell={} flushing {} offer(s)", cellId, offers.size());
                for (Offer offer : offers) {
                    sendToRamenSink(offer);
                }
            }
        }
    }

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
