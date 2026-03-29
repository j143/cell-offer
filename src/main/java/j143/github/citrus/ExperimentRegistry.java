package j143.github.citrus;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, validated registry of experiments loaded from {@code experiments.yaml}.
 *
 * <p><strong>Zero-tolerance conflict rule:</strong> if two or more <em>enabled</em>
 * experiments declare overrides for the same parameter key, the constructor
 * throws a {@link FatalConfigException}. "Last-applied wins" semantics are
 * rejected because they produce silent data corruption in A/B analyses – each
 * experiment assumes exclusive ownership of the parameters it overrides.
 *
 * <p>Instances are immutable and safe to share across threads. The
 * {@link ExperimentClient} swaps the entire registry reference atomically when
 * new configuration is polled.
 */
public final class ExperimentRegistry {

    private final List<Experiment> experiments;

    /**
     * Index from parameter key → the single experiment that owns it.
     * Built and conflict-checked at construction time.
     */
    private final Map<String, Experiment> paramIndex;

    /**
     * Constructs an empty registry (no active experiments).
     * All {@link ExperimentClient#getIntParam} calls will return their default values.
     */
    public ExperimentRegistry() {
        this.experiments = List.of();
        this.paramIndex  = Map.of();
    }

    /**
     * Constructs a registry from the given list of experiments.
     *
     * @param experiments list of experiments (may include disabled ones)
     * @throws FatalConfigException if two enabled experiments override the same
     *                              parameter key
     */
    public ExperimentRegistry(List<Experiment> experiments) {
        this.experiments = Collections.unmodifiableList(List.copyOf(experiments));
        this.paramIndex  = buildParamIndex(experiments);
    }

    /**
     * Returns the enabled experiment that owns {@code paramKey}, if any.
     *
     * <p>This is the hot path – called on every {@code getParam()} invocation.
     * The {@link HashMap} lookup is O(1).
     */
    public Optional<Experiment> getExperimentForParameter(String paramKey) {
        return Optional.ofNullable(paramIndex.get(paramKey));
    }

    /** Returns all experiments in this registry (including disabled ones). */
    public List<Experiment> getExperiments() {
        return experiments;
    }

    // -----------------------------------------------------------------------

    private static Map<String, Experiment> buildParamIndex(List<Experiment> experiments) {
        Map<String, Experiment> index = new HashMap<>();

        for (Experiment exp : experiments) {
            if (!exp.isEnabled()) continue;

            for (String paramKey : exp.getParameterKeys()) {
                Experiment previous = index.put(paramKey, exp);
                if (previous != null) {
                    throw new FatalConfigException(
                            String.format(
                                    "Parameter key conflict: both experiment '%s' and '%s' "
                                    + "declare an override for parameter '%s'. "
                                    + "Disable one of them or use separate parameter keys.",
                                    previous.getName(), exp.getName(), paramKey));
                }
            }
        }

        return Collections.unmodifiableMap(index);
    }
}
