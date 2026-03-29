package j143.github.citrus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link ExperimentRegistry}'s zero-tolerance conflict-detection rule.
 *
 * <p>Two enabled experiments that override the same parameter key must cause a
 * {@link FatalConfigException} at registry construction time.
 */
class ExperimentRegistryTest {

    private static Experiment experiment(String name, String paramKey) {
        return Experiment.builder()
                .name(name)
                .enabled(true)
                .unitType("DRIVER")
                .addBucketRange(5000, "control")
                .addBucketRange(10000, "treatment")
                .addParameterOverride(paramKey, Map.of("control", 3, "treatment", 10))
                .build();
    }

    @Test
    void constructor_acceptsNonConflictingExperiments() {
        Experiment expA = experiment("ExpA", "param.key.a");
        Experiment expB = experiment("ExpB", "param.key.b");

        ExperimentRegistry registry = new ExperimentRegistry(List.of(expA, expB));

        assertThat(registry.getExperimentForParameter("param.key.a"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.getName()).isEqualTo("ExpA"));
        assertThat(registry.getExperimentForParameter("param.key.b"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.getName()).isEqualTo("ExpB"));
    }

    @Test
    void constructor_throwsFatalConfigException_onParameterKeyConflict() {
        // Two enabled experiments each claiming the same parameter key.
        Experiment expA = experiment("ExpA", "shared.param");
        Experiment expB = experiment("ExpB", "shared.param");

        assertThatThrownBy(() -> new ExperimentRegistry(List.of(expA, expB)))
                .isInstanceOf(FatalConfigException.class)
                .hasMessageContaining("shared.param")
                .hasMessageContaining("ExpA")
                .hasMessageContaining("ExpB");
    }

    @Test
    void constructor_ignoresDisabledExperiments_forConflictCheck() {
        // One enabled, one disabled – should NOT throw even if they share a key.
        Experiment enabled = experiment("Enabled", "shared.param");
        Experiment disabled = Experiment.builder()
                .name("Disabled")
                .enabled(false)   // <-- disabled
                .unitType("DRIVER")
                .addBucketRange(10000, "control")
                .addParameterOverride("shared.param", Map.of("control", 99))
                .build();

        // Must not throw
        ExperimentRegistry registry = new ExperimentRegistry(List.of(enabled, disabled));
        assertThat(registry.getExperimentForParameter("shared.param"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.getName()).isEqualTo("Enabled"));
    }

    @Test
    void emptyConstructor_returnsEmptyRegistry() {
        ExperimentRegistry empty = new ExperimentRegistry();
        assertThat(empty.getExperiments()).isEmpty();
        assertThat(empty.getExperimentForParameter("any.key")).isEmpty();
    }

    @Test
    void getExperimentForParameter_returnsEmpty_forUnknownKey() {
        ExperimentRegistry registry = new ExperimentRegistry(List.of(experiment("Exp", "known.key")));
        assertThat(registry.getExperimentForParameter("unknown.key")).isEmpty();
    }
}
