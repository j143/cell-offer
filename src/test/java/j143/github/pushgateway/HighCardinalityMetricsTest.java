package j143.github.pushgateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import j143.github.pushgateway.model.ClientSession;
import j143.github.push.proto.ServerToClient;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Demonstrates Bug 7: High-Cardinality Metric Explosion.
 *
 * <h3>What the bug is</h3>
 * <p>{@link Dispatcher#drainIfReady} now tags the {@code push_delivery_latency}
 * timer with both {@code userId} <em>and</em> {@code seqId}. Every unique
 * {@code seqId} (which increments monotonically and is never reused) creates a
 * brand-new time-series in the Micrometer / Prometheus registry.
 *
 * <h3>What happens in production</h3>
 * <ul>
 *   <li>At 1 000 messages/s the registry accumulates 1 000 new timer series
 *       every second.</li>
 *   <li>The Prometheus {@code /actuator/prometheus} scrape payload grows without
 *       bound, taking seconds to generate and megabytes to transfer.</li>
 *   <li>Grafana dashboards become unusable because each panel must aggregate
 *       millions of series.</li>
 *   <li>The Micrometer registry holds strong references to all timers, causing
 *       heap growth and eventual OOM.</li>
 * </ul>
 *
 * <h3>How to reproduce</h3>
 * <ol>
 *   <li>Start the service and enqueue 10 000 messages.</li>
 *   <li>Scrape {@code /actuator/prometheus} and observe the number of
 *       {@code push_delivery_latency_*} lines: it equals the number of distinct
 *       seqId values sent.</li>
 * </ol>
 *
 * <h3>Fix</h3>
 * <ul>
 *   <li>Remove the {@code "seqId"} tag from {@code push_delivery_latency}.</li>
 *   <li>Keep only low-cardinality tags (e.g. {@code "priority"} bucket, or no
 *       user-level tag at all – use a single global timer).</li>
 *   <li>Log high-cardinality detail (seqId, userId, deviceId) as structured log
 *       events instead of metric tags.</li>
 * </ul>
 */
class HighCardinalityMetricsTest {

    private ConnectionManager   connectionManager;
    private MessageStore        messageStore;
    private SimpleMeterRegistry meterRegistry;
    private Dispatcher          dispatcher;

    @BeforeEach
    void setUp() {
        connectionManager = new ConnectionManager();
        meterRegistry     = new SimpleMeterRegistry();
        messageStore      = new MessageStore(500, meterRegistry);
        dispatcher        = new Dispatcher(connectionManager, messageStore, meterRegistry, 50, 30_000);
    }

    /**
     * BUG DEMONSTRATION: one timer series created per dispatched message.
     *
     * <p>With the bug present, the {@code push_delivery_latency} timer is tagged
     * with {@code seqId}. Sending N messages creates N distinct timer series in
     * the registry.
     *
     * <p><strong>After the fix:</strong> there should be exactly one
     * {@code push_delivery_latency} series (or at most one per low-cardinality
     * bucket), regardless of how many messages are sent.
     */
    @Test
    void bugPresent_eachMessageCreatesNewTimerSeries() {
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerToClient> obs = mock(ServerCallStreamObserver.class);
        when(obs.isReady()).thenReturn(true);

        ClientSession session = new ClientSession("driver-hc", "dev-hc", obs, 0L);
        connectionManager.registerSession("driver-hc", session);

        int messageCount = 20;
        for (int i = 0; i < messageCount; i++) {
            messageStore.enqueue("driver-hc", ("offer-" + i).getBytes(), Duration.ofSeconds(60), 5);
        }

        dispatcher.drainIfReady("driver-hc");

        // Count distinct push_delivery_latency timers after dispatch
        long timerSeriesCount = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("push_delivery_latency"))
                .count();

        // BUG: timerSeriesCount equals messageCount because each seqId is a unique tag value.
        // Fix will make this 1 (a single global or low-cardinality-bucket timer).
        assertThat(timerSeriesCount)
                .as("BUG: %d timer series created for %d messages – one per seqId. "
                        + "Fix: remove the seqId tag; use a single or low-cardinality timer.",
                        timerSeriesCount, messageCount)
                .isGreaterThan(1);
    }

    /**
     * BUG DEMONSTRATION: userId tag on counters and gauges is also high cardinality.
     *
     * <p>Even before the seqId bug, tagging metrics with raw {@code userId} means
     * the number of metric series grows linearly with the number of connected
     * drivers. At Uber scale (millions of drivers) this is untenable.
     *
     * <p><strong>This test documents the pre-existing high-cardinality design:</strong>
     * {@code push_queue_depth} and {@code push_messages_sent_total} both carry a
     * {@code userId} tag. The registry grows without bound as new users connect.
     */
    @Test
    void bugPresent_userIdTagCausesLinearMetricGrowth() {
        int userCount = 30;
        for (int i = 0; i < userCount; i++) {
            messageStore.enqueue("driver-" + i, "payload".getBytes(), Duration.ofSeconds(60), 5);
        }

        long uniqueGaugeSeries = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("push_queue_depth"))
                .map(m -> m.getId().getTag("userId"))
                .distinct()
                .count();

        // BUG: one gauge series per userId – linear metric growth with driver count.
        // Fix: aggregate by shard/cell bucket, not individual driver ID;
        // or drop the userId tag and use a global queue-depth gauge + structured log.
        assertThat(uniqueGaugeSeries)
                .as("BUG: %d push_queue_depth gauge series – one per userId. "
                        + "Fix: use low-cardinality tags (e.g. shard bucket) or a global gauge.",
                        uniqueGaugeSeries)
                .isEqualTo(userCount);
    }
}
