package j143.github.citrus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and hot-reloads experiments from a YAML configuration file.
 *
 * <p>On startup ({@link #reload} is called via {@link PostConstruct}) and then
 * every {@code citrus.config.poll-interval-ms} milliseconds (default 60 s),
 * this component:
 * <ol>
 *   <li>Reads and parses the YAML file at {@code citrus.config.path} (or the
 *       classpath resource {@code experiments.yaml} as a fallback).</li>
 *   <li>Builds an {@link ExperimentRegistry}, which validates that no two
 *       enabled experiments override the same parameter key.</li>
 *   <li>Calls {@link ExperimentClient#updateConfig} to atomically swap the
 *       live registry.</li>
 * </ol>
 *
 * <p>If parsing or validation fails, the existing live registry is preserved
 * and the error is logged. This prevents a bad config push from taking down
 * the service.
 *
 * <p><strong>YAML format</strong> (see {@code src/main/resources/experiments.yaml}):
 * <pre>{@code
 * experiments:
 *   - name: "RamenBackoffV2"
 *     enabled: true
 *     unitType: "DRIVER"
 *     trafficSplits:
 *       control:   5000
 *       treatment: 5000
 *     parameterOverrides:
 *       ramen.retry.max-attempts:
 *         control:   3
 *         treatment: 10
 * }</pre>
 */
@Component
public class ExperimentsConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ExperimentsConfigLoader.class);

    private final ExperimentClient experimentClient;
    private final String           configPath;

    public ExperimentsConfigLoader(
            ExperimentClient experimentClient,
            @Value("${citrus.config.path:}") String configPath) {
        this.experimentClient = experimentClient;
        this.configPath       = configPath;
    }

    /** Loads the config at startup so the first request does not see an empty registry. */
    @PostConstruct
    public void reload() {
        try {
            ExperimentRegistry registry = loadRegistry();
            experimentClient.updateConfig(registry);
            log.info("[Citrus] Loaded {} experiment(s) from config",
                    registry.getExperiments().size());
        } catch (FatalConfigException e) {
            // Re-throw fatal conflicts – these are programmer errors that must be fixed.
            throw e;
        } catch (Exception e) {
            log.warn("[Citrus] Failed to load experiments config, keeping previous state: {}",
                    e.getMessage());
        }
    }

    /** Periodically re-reads the config file without restarting the JVM. */
    @Scheduled(fixedDelayString = "${citrus.config.poll-interval-ms:60000}")
    public void scheduledReload() {
        reload();
    }

    // -----------------------------------------------------------------------

    ExperimentRegistry loadRegistry() throws IOException {
        try (InputStream in = openConfigStream()) {
            if (in == null) {
                log.debug("[Citrus] No experiments config found; using empty registry");
                return new ExperimentRegistry();
            }
            return parseRegistry(in);
        }
    }

    private InputStream openConfigStream() throws IOException {
        if (configPath != null && !configPath.isBlank()) {
            Path p = Paths.get(configPath);
            if (Files.exists(p)) {
                log.debug("[Citrus] Loading experiments from file: {}", configPath);
                return Files.newInputStream(p);
            }
            log.warn("[Citrus] Config path '{}' does not exist; falling back to classpath", configPath);
        }
        InputStream cp = getClass().getClassLoader().getResourceAsStream("experiments.yaml");
        if (cp != null) {
            log.debug("[Citrus] Loading experiments from classpath: experiments.yaml");
        }
        return cp;
    }

    @SuppressWarnings("unchecked")
    ExperimentRegistry parseRegistry(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);
        if (root == null || !root.containsKey("experiments")) {
            return new ExperimentRegistry();
        }

        List<Map<String, Object>> rawExperiments =
                (List<Map<String, Object>>) root.get("experiments");

        List<Experiment> experiments = new ArrayList<>();
        for (Map<String, Object> raw : rawExperiments) {
            experiments.add(parseExperiment(raw));
        }

        // ExperimentRegistry constructor validates for parameter-key conflicts.
        return new ExperimentRegistry(experiments);
    }

    @SuppressWarnings("unchecked")
    private Experiment parseExperiment(Map<String, Object> raw) {
        String  name     = (String)  raw.get("name");
        boolean enabled  = Boolean.TRUE.equals(raw.get("enabled"));
        String  unitType = (String)  raw.get("unitType");

        Map<String, Integer> splits =
                (Map<String, Integer>) raw.getOrDefault("trafficSplits", new LinkedHashMap<>());

        Map<String, Map<String, Object>> overrides =
                (Map<String, Map<String, Object>>) raw.getOrDefault(
                        "parameterOverrides", new HashMap<>());

        Experiment.Builder builder = Experiment.builder()
                .name(name)
                .enabled(enabled)
                .unitType(unitType);

        // Build cumulative bucket ranges in insertion order.
        int cumulative = 0;
        for (Map.Entry<String, Integer> entry : splits.entrySet()) {
            cumulative += entry.getValue();
            builder.addBucketRange(cumulative, entry.getKey());
        }

        for (Map.Entry<String, Map<String, Object>> entry : overrides.entrySet()) {
            builder.addParameterOverride(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }
}
