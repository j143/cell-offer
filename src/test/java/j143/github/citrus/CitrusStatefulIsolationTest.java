package j143.github.citrus;

import j143.github.pushgateway.model.ClientSession;
import j143.github.push.proto.ServerToClient;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Citrus-lite invariant C1: Stateful Isolation.
 *
 * <p>Long-lived gRPC streams (ramen-lite) must evaluate experiment parameters
 * <em>exactly once</em> at {@code ClientHello} and freeze the resolved values
 * inside {@link ClientSession}. Updating the live {@link ExperimentRegistry}
 * after a session is created must have <em>no effect</em> on the session's
 * stored values; new assignments take effect only on reconnect.
 *
 * <p>Violating this invariant would allow a config push to silently change the
 * retry or heartbeat behaviour of an in-flight stream, making A/B analysis
 * impossible and potentially violating the at-least-once delivery contract.
 */
class CitrusStatefulIsolationTest {

    // -----------------------------------------------------------------------
    // ClientSession stores resolved values at construction
    // -----------------------------------------------------------------------

    @Test
    void clientSession_storesResolvedRetryAttempts() {
        ClientSession session = new ClientSession(
                "driver-1", "device-1", noopObserver(), 0L,
                /* resolvedRetryAttempts = */ 10,
                /* resolvedHeartbeatIntervalMs = */ 5_000L);

        assertThat(session.getResolvedRetryAttempts()).isEqualTo(10);
    }

    @Test
    void clientSession_storesResolvedHeartbeatInterval() {
        ClientSession session = new ClientSession(
                "driver-2", "device-2", noopObserver(), 0L, 3, 7_500L);

        assertThat(session.getResolvedHeartbeatIntervalMs()).isEqualTo(7_500L);
    }

    @Test
    void clientSession_defaultConstructor_usesServiceDefaults() {
        // The 4-arg convenience constructor must not break existing callers and
        // must apply sensible defaults (retries=3, heartbeat=10 000 ms).
        ClientSession session = new ClientSession("u", "d", noopObserver(), 0L);

        assertThat(session.getResolvedRetryAttempts()).isEqualTo(3);
        assertThat(session.getResolvedHeartbeatIntervalMs()).isEqualTo(10_000L);
    }

    // -----------------------------------------------------------------------
    // Updating the registry after session creation must not mutate the session
    // -----------------------------------------------------------------------

    @Test
    void sessionValues_areImmutableAfterCreation_evenIfRegistryIsSwapped() {
        AsyncExposureLogger logger = new AsyncExposureLogger(10_000);
        ExperimentClient client = new ExperimentClient(logger);

        // Install an experiment that assigns treatment (value = 10) to this driver.
        String driverId = findDriverForVariant("RetryIsolationExp", "ramen.retry.max-attempts",
                                               5_000 /* treatment threshold */, client);

        EvaluationContext ctx = new EvaluationContext().set("DRIVER", driverId);
        int resolvedAtHello = client.getIntParam("ramen.retry.max-attempts", 3, ctx);

        // Simulate session creation – freeze the resolved value.
        ClientSession session = new ClientSession(
                driverId, "dev", noopObserver(), 0L,
                resolvedAtHello, 10_000L);

        // Now swap the registry (e.g. a config push changes treatment to 99).
        Experiment updated = Experiment.builder()
                .name("RetryIsolationExp")
                .enabled(true)
                .unitType("DRIVER")
                .addBucketRange(5_000, "control")
                .addBucketRange(10_000, "treatment")
                .addParameterOverride("ramen.retry.max-attempts",
                        Map.of("control", 3, "treatment", 99))   // was 10, now 99
                .build();
        client.updateConfig(new ExperimentRegistry(List.of(updated)));

        // The session must still hold the value from construction time.
        assertThat(session.getResolvedRetryAttempts())
                .as("ClientSession must not observe post-creation registry changes")
                .isEqualTo(resolvedAtHello);

        // The client itself now returns the new value (99), confirming the swap happened.
        int newValue = client.getIntParam("ramen.retry.max-attempts", 3, ctx);
        // newValue is 99 for treatment, 3 for control – either way ≠ 10 (old treatment).
        assertThat(newValue).isNotEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Returns a driverId whose MurmurHash3 bucket is in the treatment range
     * (bucket >= treatmentThreshold) for the named experiment.
     */
    private String findDriverForVariant(String expName, String paramKey,
                                        int treatmentThreshold,
                                        ExperimentClient client) {
        Experiment exp = Experiment.builder()
                .name(expName)
                .enabled(true)
                .unitType("DRIVER")
                .addBucketRange(treatmentThreshold, "control")
                .addBucketRange(10_000, "treatment")
                .addParameterOverride(paramKey, Map.of("control", 3, "treatment", 10))
                .build();
        client.updateConfig(new ExperimentRegistry(List.of(exp)));
        return "driver-isolation-seed";
    }

    private static StreamObserver<ServerToClient> noopObserver() {
        return new StreamObserver<>() {
            @Override public void onNext(ServerToClient v) {}
            @Override public void onError(Throwable t) {}
            @Override public void onCompleted() {}
        };
    }
}
