package j143.github.pushgateway;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that prove Micrometer gauges are registered exactly once per user,
 * preventing the OOM caused by duplicate gauge registrations.
 *
 * <p>Critical flaw being fixed:
 * <ul>
 *   <li>{@code MessageStore.recordEnqueued()} previously called
 *       {@code meterRegistry.gauge()} on every single enqueue, so after N
 *       messages for the same user, N duplicate gauge registrations were
 *       created in the metrics registry – eventually causing OOM.</li>
 *   <li>{@code PushServiceImpl.handleHello()} similarly registered
 *       {@code active_sessions} on every client connection.</li>
 * </ul>
 *
 * <p>Correct behaviour: gauge registration must occur exactly once per logical
 * entity (once per user queue; once per service instance).
 */
class GaugeMeterRegistrationTest {

    @Test
    void pushQueueDepth_gaugeRegisteredOncePerUser_notOnEveryEnqueue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessageStore store = new MessageStore(50, registry);

        // Enqueue several messages for the same user
        for (int i = 0; i < 20; i++) {
            store.enqueue("user-gauge", "msg".getBytes(), Duration.ofSeconds(30), i % 5);
        }

        // Only one gauge with name "push_queue_depth" and tag userId=user-gauge
        List<Meter> gauges = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("push_queue_depth"))
                .filter(m -> "user-gauge".equals(m.getId().getTag("userId")))
                .toList();

        assertThat(gauges)
                .as("push_queue_depth gauge must be registered exactly once per user, "
                        + "not once per enqueue – duplicate gauges cause OOM")
                .hasSize(1);
    }

    @Test
    void pushQueueDepth_separateGaugePerUser_noSharedState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessageStore store = new MessageStore(50, registry);

        store.enqueue("user-A", "a".getBytes(), Duration.ofSeconds(30), 1);
        store.enqueue("user-B", "b".getBytes(), Duration.ofSeconds(30), 1);
        store.enqueue("user-B", "c".getBytes(), Duration.ofSeconds(30), 2);

        // Each user must have their own gauge, registered exactly once
        long gaugesA = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("push_queue_depth"))
                .filter(m -> "user-A".equals(m.getId().getTag("userId")))
                .count();
        long gaugesB = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("push_queue_depth"))
                .filter(m -> "user-B".equals(m.getId().getTag("userId")))
                .count();

        assertThat(gaugesA).isEqualTo(1);
        assertThat(gaugesB).isEqualTo(1);
    }

    @Test
    void pushQueueDepth_gaugeValueReflectsCurrentDepth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessageStore store = new MessageStore(50, registry);

        store.enqueue("user-C", "m1".getBytes(), Duration.ofSeconds(30), 5);
        store.enqueue("user-C", "m2".getBytes(), Duration.ofSeconds(30), 4);

        double gaugeValue = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("push_queue_depth"))
                .filter(m -> "user-C".equals(m.getId().getTag("userId")))
                .findFirst()
                .map(m -> ((io.micrometer.core.instrument.Gauge) m).value())
                .orElse(-1.0);

        assertThat(gaugeValue)
                .as("Gauge must report the current queue depth")
                .isEqualTo(2.0);
    }
}
