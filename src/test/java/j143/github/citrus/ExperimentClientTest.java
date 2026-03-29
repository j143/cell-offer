package j143.github.citrus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the hot-path evaluation logic of {@link ExperimentClient}.
 */
class ExperimentClientTest {

    private AsyncExposureLogger exposureLogger;
    private ExperimentClient    client;

    @BeforeEach
    void setUp() {
        exposureLogger = new AsyncExposureLogger(10_000);
        client         = new ExperimentClient(exposureLogger);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a simple 50/50 experiment overriding a single int parameter. */
    private Experiment fiftyFifty(String name, String paramKey, int controlVal, int treatmentVal) {
        return Experiment.builder()
                .name(name)
                .enabled(true)
                .unitType("DRIVER")
                .addBucketRange(5000, "control")
                .addBucketRange(10_000, "treatment")
                .addParameterOverride(paramKey, Map.of(
                        "control",   controlVal,
                        "treatment", treatmentVal))
                .build();
    }

    // -----------------------------------------------------------------------

    @Test
    void getIntParam_returnsDefault_whenNoExperimentActive() {
        // Registry is empty
        EvaluationContext ctx = new EvaluationContext().set("DRIVER", "driver-1");
        int result = client.getIntParam("ramen.retry.max-attempts", 3, ctx);
        assertThat(result).isEqualTo(3);
    }

    @Test
    void getIntParam_returnsDefault_whenContextMissingUnitType() {
        Experiment exp = fiftyFifty("Exp", "ramen.retry.max-attempts", 3, 10);
        client.updateConfig(new ExperimentRegistry(List.of(exp)));

        // Context has RIDER but experiment expects DRIVER
        EvaluationContext ctx = new EvaluationContext().set("RIDER", "rider-1");
        int result = client.getIntParam("ramen.retry.max-attempts", 3, ctx);
        assertThat(result).isEqualTo(3);
    }

    @Test
    void getIntParam_returnsOverrideValue_whenExperimentActive() {
        Experiment exp = fiftyFifty("RetryExp", "ramen.retry.max-attempts", 3, 10);
        client.updateConfig(new ExperimentRegistry(List.of(exp)));

        // Evaluate a fixed driver whose bucket we can compute.
        String driverId = "driver-deterministic";
        int bucket = ExperimentHasher.getBucket(driverId, "RetryExp");
        int expected = bucket < 5000 ? 3 : 10;

        EvaluationContext ctx = new EvaluationContext().set("DRIVER", driverId);
        int result = client.getIntParam("ramen.retry.max-attempts", 99, ctx);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getLongParam_returnsOverrideValue_whenExperimentActive() {
        Experiment exp = Experiment.builder()
                .name("HeartbeatExp")
                .enabled(true)
                .unitType("DRIVER")
                .addBucketRange(5000, "control")
                .addBucketRange(10_000, "treatment")
                .addParameterOverride("ramen.heartbeat.interval-ms",
                        Map.of("control", 10_000, "treatment", 5_000))
                .build();
        client.updateConfig(new ExperimentRegistry(List.of(exp)));

        String driverId = "driver-hb";
        int bucket = ExperimentHasher.getBucket(driverId, "HeartbeatExp");
        long expected = bucket < 5000 ? 10_000L : 5_000L;

        EvaluationContext ctx = new EvaluationContext().set("DRIVER", driverId);
        long result = client.getLongParam("ramen.heartbeat.interval-ms", 99_000L, ctx);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void updateConfig_swapsRegistryAtomically() {
        EvaluationContext ctx = new EvaluationContext().set("DRIVER", "driver-swap");

        // Initially no experiment → default
        assertThat(client.getIntParam("my.param", 1, ctx)).isEqualTo(1);

        // Install an experiment that overrides the param
        Experiment exp = fiftyFifty("SwapExp", "my.param", 42, 99);
        client.updateConfig(new ExperimentRegistry(List.of(exp)));

        // Now the client must return an override (either 42 or 99, not 1)
        int result = client.getIntParam("my.param", 1, ctx);
        assertThat(result).isIn(42, 99);
    }

    @Test
    void getIntParam_assignmentIsStable_forSameUnit() {
        Experiment exp = fiftyFifty("StableExp", "p", 100, 200);
        client.updateConfig(new ExperimentRegistry(List.of(exp)));

        EvaluationContext ctx = new EvaluationContext().set("DRIVER", "driver-stable");
        int first  = client.getIntParam("p", 0, ctx);
        int second = client.getIntParam("p", 0, ctx);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void getIntParam_disabledExperiment_returnsDefault() {
        Experiment exp = Experiment.builder()
                .name("DisabledExp")
                .enabled(false)
                .unitType("DRIVER")
                .addBucketRange(10_000, "treatment")
                .addParameterOverride("my.flag", Map.of("treatment", 99))
                .build();
        client.updateConfig(new ExperimentRegistry(List.of(exp)));

        EvaluationContext ctx = new EvaluationContext().set("DRIVER", "driver-1");
        assertThat(client.getIntParam("my.flag", 1, ctx)).isEqualTo(1);
    }
}
