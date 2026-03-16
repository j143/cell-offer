package com.uber.celloffer.scheduler;

import com.uber.celloffer.model.Offer;
import com.uber.celloffer.queue.CellOfferQueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodic flush scheduler that drains each cell queue and forwards live offers
 * to the downstream RAMEN sink (simulated here as a log statement or a call to
 * {@code /ramen-sink}).
 *
 * <p>The interval and the mock-sink URL are configurable via
 * {@code application.properties}.
 */
@Component
public class OfferFlusher {

    private static final Logger log = LoggerFactory.getLogger(OfferFlusher.class);

    private final CellOfferQueueManager queueManager;

    public OfferFlusher(CellOfferQueueManager queueManager) {
        this.queueManager = queueManager;
    }

    /**
     * Fires every {@code cell.offer.flush-interval-ms} milliseconds (default 300 ms).
     *
     * <p>For every registered cell it:
     * <ol>
     *   <li>Calls {@link CellOfferQueueManager#flushDueOffers} to drain non-expired offers.</li>
     *   <li>Logs each flushed offer (simulating the RAMEN push).</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${cell.offer.flush-interval-ms:300}")
    public void flush() {
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

    /**
     * Simulates sending an offer downstream.
     * In a real system this would publish to RAMEN / gRPC stream.
     */
    private void sendToRamenSink(Offer offer) {
        log.info("[RAMEN-SINK] offerId={} cellId={} driverId={} riderId={} priority={}",
                offer.getOfferId(),
                offer.getCellId(),
                offer.getDriverId(),
                offer.getRiderId(),
                offer.getPriority());
    }
}
