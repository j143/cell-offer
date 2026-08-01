package j143.github.celloffer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import j143.github.celloffer.dto.EnqueueOfferRequest;
import j143.github.celloffer.model.CellQueueStats;
import j143.github.celloffer.model.Offer;
import j143.github.celloffer.queue.CellOfferQueueManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for the DispatchCellQueue service.
 *
 * <pre>
 * POST /cells/{cellId}/offers    – enqueue a driver offer for a cell
 * GET  /cells/{cellId}/stats     – return current stats for the cell
 * GET  /cells/stats              – return stats for all cells
 * </pre>
 */
@Tag(name = "Dispatch", description = "H3-cell offer queue operations")
@RestController
@RequestMapping("/cells")
public class DispatchController {

    private final CellOfferQueueManager queueManager;

    public DispatchController(CellOfferQueueManager queueManager) {
        this.queueManager = queueManager;
    }

    /**
     * Enqueues a driver offer for the given H3 cell.
     *
     * @param cellId  the H3 cell identifier (path variable)
     * @param request body containing {@code driverId}, {@code riderId},
     *                {@code priority}, and {@code ttlMillis}
     * @return 202 Accepted with the generated {@code offerId}
     */
    @Operation(
        summary = "Enqueue a driver offer",
        description = "Push a driver offer into the bounded priority queue for a given H3 cell."
    )
    @ApiResponse(responseCode = "202", description = "Offer accepted, returns offerId")
    @PostMapping("/{cellId}/offers")
    public ResponseEntity<Map<String, String>> enqueueOffer(
            @PathVariable String cellId,
            @RequestBody EnqueueOfferRequest request) {

        String offerId = UUID.randomUUID().toString();
        Offer offer = Offer.builder()
                .offerId(offerId)
                .cellId(cellId)
                .driverId(request.getDriverId())
                .riderId(request.getRiderId())
                .priority(request.getPriority())
                .createdAt(Instant.now())
                .ttl(Duration.ofMillis(request.getTtlMillis()))
                .build();

        queueManager.enqueue(offer);
        return ResponseEntity.accepted()
                .body(Map.of("offerId", offerId, "cellId", cellId));
    }

    /**
     * Returns the current queue depth and counters for the given cell.
     *
     * @param cellId the H3 cell identifier (path variable)
     * @return 200 OK with a JSON payload containing stats and live queue depth
     */
    @Operation(summary = "Get stats for a single cell")
    @ApiResponse(responseCode = "200", description = "Cell queue stats")
    @GetMapping("/{cellId}/stats")
    public ResponseEntity<Map<String, Object>> getCellStats(
            @PathVariable String cellId) {

        CellQueueStats stats = queueManager.getStats(cellId);
        int depth = queueManager.getQueueDepth(cellId);

        return ResponseEntity.ok(Map.of(
                "cellId", cellId,
                "queueDepth", depth,
                "enqueued", stats.getEnqueued(),
                "flushed", stats.getFlushed(),
                "expired", stats.getExpired(),
                "droppedOnEnqueue", stats.getDroppedOnEnqueue()
        ));
    }

    /**
     * Returns an aggregate view of stats for every known cell.
     *
     * @return 200 OK with a map of cellId → stats payload
     */
    @Operation(summary = "Get aggregate stats for all cells")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Map<String, Object>>> getAllStats() {
        Map<String, CellQueueStats> all = queueManager.getAllStats();
        Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, CellQueueStats> entry : all.entrySet()) {
            String cellId = entry.getKey();
            CellQueueStats s = entry.getValue();
            result.put(cellId, Map.of(
                    "queueDepth", queueManager.getQueueDepth(cellId),
                    "enqueued", s.getEnqueued(),
                    "flushed", s.getFlushed(),
                    "expired", s.getExpired(),
                    "droppedOnEnqueue", s.getDroppedOnEnqueue()
            ));
        }
        return ResponseEntity.ok(result);
    }
}
