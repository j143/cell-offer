package j143.github.citrus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link ExperimentsConfigLoader}: YAML parsing, conflict detection on
 * load, and the classpath fallback.
 */
class ExperimentsConfigLoaderTest {

    private AsyncExposureLogger  logger;
    private ExperimentClient     client;
    private ExperimentsConfigLoader loader;

    @BeforeEach
    void setUp() {
        logger  = new AsyncExposureLogger(10_000);
        client  = new ExperimentClient(logger);
        // empty config path → classpath fallback
        loader  = new ExperimentsConfigLoader(client, "");
    }

    // -----------------------------------------------------------------------
    // YAML parsing helpers
    // -----------------------------------------------------------------------

    private ExperimentRegistry parseYaml(String yaml) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(
                yaml.getBytes(StandardCharsets.UTF_8));
        return loader.parseRegistry(in);
    }

    // -----------------------------------------------------------------------

    @Test
    void parseRegistry_loadsEnabledExperiment() throws IOException {
        String yaml = """
                experiments:
                  - name: "TestExp"
                    enabled: true
                    unitType: "DRIVER"
                    trafficSplits:
                      control: 5000
                      treatment: 5000
                    parameterOverrides:
                      ramen.retry.max-attempts:
                        control: 3
                        treatment: 10
                """;
        ExperimentRegistry registry = parseYaml(yaml);

        assertThat(registry.getExperiments()).hasSize(1);
        Experiment exp = registry.getExperiments().get(0);
        assertThat(exp.getName()).isEqualTo("TestExp");
        assertThat(exp.isEnabled()).isTrue();
        assertThat(exp.getUnitType()).isEqualTo("DRIVER");
        assertThat(exp.getParameterKeys()).containsExactly("ramen.retry.max-attempts");
    }

    @Test
    void parseRegistry_bucketRangesReflectTrafficSplits() throws IOException {
        String yaml = """
                experiments:
                  - name: "BucketExp"
                    enabled: true
                    unitType: "DRIVER"
                    trafficSplits:
                      control: 7000
                      treatment: 3000
                    parameterOverrides:
                      p:
                        control: 1
                        treatment: 2
                """;
        ExperimentRegistry registry = parseYaml(yaml);
        Experiment exp = registry.getExperiments().get(0);

        // Bucket 0 → 6999 should be control; 7000 → 9999 should be treatment
        assertThat(exp.getVariantForBucket(0)).isEqualTo("control");
        assertThat(exp.getVariantForBucket(6999)).isEqualTo("control");
        assertThat(exp.getVariantForBucket(7000)).isEqualTo("treatment");
        assertThat(exp.getVariantForBucket(9999)).isEqualTo("treatment");
    }

    @Test
    void parseRegistry_throwsFatalConfigException_onConflict() {
        String yaml = """
                experiments:
                  - name: "ExpA"
                    enabled: true
                    unitType: "DRIVER"
                    trafficSplits:
                      control: 10000
                    parameterOverrides:
                      shared.param:
                        control: 1
                  - name: "ExpB"
                    enabled: true
                    unitType: "DRIVER"
                    trafficSplits:
                      control: 10000
                    parameterOverrides:
                      shared.param:
                        control: 2
                """;

        assertThatThrownBy(() -> parseYaml(yaml))
                .isInstanceOf(FatalConfigException.class)
                .hasMessageContaining("shared.param");
    }

    @Test
    void parseRegistry_disabledExperimentDoesNotConflict() throws IOException {
        String yaml = """
                experiments:
                  - name: "Enabled"
                    enabled: true
                    unitType: "DRIVER"
                    trafficSplits:
                      control: 10000
                    parameterOverrides:
                      p:
                        control: 1
                  - name: "Disabled"
                    enabled: false
                    unitType: "DRIVER"
                    trafficSplits:
                      control: 10000
                    parameterOverrides:
                      p:
                        control: 99
                """;
        ExperimentRegistry registry = parseYaml(yaml);
        // Should not throw and should only index the enabled experiment.
        assertThat(registry.getExperimentForParameter("p"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.getName()).isEqualTo("Enabled"));
    }

    @Test
    void parseRegistry_emptyOrMissingExperimentsKey_returnsEmptyRegistry() throws IOException {
        assertThat(parseYaml("{}").getExperiments()).isEmpty();
        assertThat(parseYaml("experiments: []").getExperiments()).isEmpty();
    }

    @Test
    void reload_loadsClasspathFallback() {
        // The actual classpath experiments.yaml exists and has at least one entry.
        loader.reload();
        List<Experiment> experiments = client.getCurrentRegistry().getExperiments();
        assertThat(experiments).isNotEmpty();
    }

    @Test
    void reload_loadsFromExternalFile(@TempDir Path tempDir) throws IOException {
        String yaml = """
                experiments:
                  - name: "FileExp"
                    enabled: true
                    unitType: "DRIVER"
                    trafficSplits:
                      control: 10000
                    parameterOverrides:
                      file.param:
                        control: 77
                """;
        Path configFile = tempDir.resolve("experiments.yaml");
        Files.writeString(configFile, yaml);

        ExperimentsConfigLoader fileLoader =
                new ExperimentsConfigLoader(client, configFile.toString());
        fileLoader.reload();

        assertThat(client.getCurrentRegistry().getExperimentForParameter("file.param"))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.getName()).isEqualTo("FileExp"));
    }
}
