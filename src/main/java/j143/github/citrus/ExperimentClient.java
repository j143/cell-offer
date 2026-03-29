package j143.github.citrus;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, sub-millisecond experiment evaluation SDK.
 *
 * <p>This is the primary entry-point for services that want to read
 * experiment-controlled parameter values. Inject it as a Spring bean and call
 * {@link #getIntParam} or {@link #getLongParam} with an {@link EvaluationContext}
 * built from the current request's unit identifiers (driver ID, rider ID, etc.).
 *
 * <h2>Thread safety</h2>
 * The live {@link ExperimentRegistry} is stored in an {@link AtomicReference}.
 * Background polling via {@link ExperimentsConfigLoader#reload} swaps the
 * reference atomically; readers never hold a lock and never see a partial state.
 *
 * <h2>Stateless vs stateful integration</h2>
 * <ul>
 *   <li><strong>Stateless</strong> (cell-offer): call {@code getIntParam} on every
 *       request. The experiment assignment is re-computed each time from the
 *       unit's hash bucket.</li>
 *   <li><strong>Stateful (RAMEN rule)</strong> (ramen-lite gRPC streams): call
 *       {@code getIntParam} <em>once</em> during {@code ClientHello} and store
 *       the resolved value inside the {@code ClientSession}. Applying a new
 *       config mid-stream would violate the at-least-once delivery contract;
 *       new values take effect on the next reconnect.</li>
 * </ul>
 *
 * <h2>Exposure logging</h2>
 * A non-blocking exposure event is fired every time an active experiment is
 * evaluated. The event is written asynchronously by {@link AsyncExposureLogger}
 * and must never block the calling thread.
 */
@Component
public class ExperimentClient {

    /** Atomic pointer to the current validated config. Swapped on every poll cycle. */
    private final AtomicReference<ExperimentRegistry> registryRef =
            new AtomicReference<>(new ExperimentRegistry());

    private final AsyncExposureLogger exposureLogger;

    public ExperimentClient(AsyncExposureLogger exposureLogger) {
        this.exposureLogger = exposureLogger;
    }

    /**
     * Swaps the live registry with a freshly loaded one.
     *
     * <p>Called by {@link ExperimentsConfigLoader} after it has parsed and
     * validated the YAML config. The registry must already have passed conflict
     * validation before being passed here.
     *
     * @param newRegistry the validated registry to install
     */
    public void updateConfig(ExperimentRegistry newRegistry) {
        registryRef.set(newRegistry);
    }

    // -----------------------------------------------------------------------
    // Hot-path evaluation methods
    // -----------------------------------------------------------------------

    /**
     * Evaluates the experiment that controls {@code paramKey} and returns the
     * integer value assigned to the unit described by {@code ctx}.
     *
     * <p>Returns {@code defaultValue} if:
     * <ul>
     *   <li>no active experiment overrides {@code paramKey}, or</li>
     *   <li>the context does not carry an ID for the experiment's unit type, or</li>
     *   <li>the experiment has no integer override for the assigned variant.</li>
     * </ul>
     *
     * <p>Must complete in under 1 ms on the hot path.
     *
     * @param paramKey     the parameter name to look up (e.g. {@code "ramen.retry.max-attempts"})
     * @param defaultValue value to return when no experiment is active for this param
     * @param ctx          unit identifiers used for deterministic bucketing
     * @return resolved integer value
     */
    public int getIntParam(String paramKey, int defaultValue, EvaluationContext ctx) {
        ExperimentRegistry registry = registryRef.get();
        Optional<Experiment> activeExp = registry.getExperimentForParameter(paramKey);

        if (activeExp.isEmpty() || !ctx.hasUnit(activeExp.get().getUnitType())) {
            return defaultValue;
        }

        Experiment exp    = activeExp.get();
        String     unitId = ctx.getUnitId(exp.getUnitType());

        int    bucket          = ExperimentHasher.getBucket(unitId, exp.getName());
        String assignedVariant = exp.getVariantForBucket(bucket);

        Integer overriddenValue = exp.getIntOverride(paramKey, assignedVariant);
        int finalValue = overriddenValue != null ? overriddenValue : defaultValue;

        exposureLogger.logExposure(unitId, exp.getName(), assignedVariant);

        return finalValue;
    }

    /**
     * Evaluates the experiment that controls {@code paramKey} and returns the
     * long value assigned to the unit described by {@code ctx}.
     *
     * <p>Semantics are identical to {@link #getIntParam}; use this variant for
     * parameters measured in milliseconds (e.g. heartbeat intervals).
     *
     * @param paramKey     the parameter name to look up
     * @param defaultValue value to return when no experiment is active for this param
     * @param ctx          unit identifiers used for deterministic bucketing
     * @return resolved long value
     */
    public long getLongParam(String paramKey, long defaultValue, EvaluationContext ctx) {
        ExperimentRegistry registry = registryRef.get();
        Optional<Experiment> activeExp = registry.getExperimentForParameter(paramKey);

        if (activeExp.isEmpty() || !ctx.hasUnit(activeExp.get().getUnitType())) {
            return defaultValue;
        }

        Experiment exp    = activeExp.get();
        String     unitId = ctx.getUnitId(exp.getUnitType());

        int    bucket          = ExperimentHasher.getBucket(unitId, exp.getName());
        String assignedVariant = exp.getVariantForBucket(bucket);

        Long overriddenValue = exp.getLongOverride(paramKey, assignedVariant);
        long finalValue = overriddenValue != null ? overriddenValue : defaultValue;

        exposureLogger.logExposure(unitId, exp.getName(), assignedVariant);

        return finalValue;
    }

    /** Returns the currently installed registry (for testing and inspection). */
    public ExperimentRegistry getCurrentRegistry() {
        return registryRef.get();
    }
}
