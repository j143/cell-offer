package j143.github.citrus;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries the identifiers needed to bucket a unit into an experiment variant.
 *
 * <p>The caller constructs a context before invoking
 * {@link ExperimentClient#getIntParam} or {@link ExperimentClient#getLongParam}.
 * Each experiment declares a {@code unitType} (e.g., {@code "DRIVER"},
 * {@code "RIDER"}). If the context does not carry the required unit-type ID,
 * the experiment is skipped and the default parameter value is returned.
 *
 * <p>Usage:
 * <pre>{@code
 * EvaluationContext ctx = new EvaluationContext()
 *         .set("DRIVER", driverId)
 *         .set("CITY",   cityCode);
 * int retries = client.getIntParam("ramen.retry.max-attempts", 3, ctx);
 * }</pre>
 */
public final class EvaluationContext {

    private final Map<String, String> units = new HashMap<>();

    /**
     * Associates the given {@code unitType} with {@code unitId} in this context.
     *
     * @param unitType case-sensitive type name (must match the experiment's {@code unitType})
     * @param unitId   the identifier of this unit (e.g. a driver UUID)
     * @return {@code this} for fluent chaining
     */
    public EvaluationContext set(String unitType, String unitId) {
        units.put(unitType, unitId);
        return this;
    }

    /** Returns {@code true} if this context carries an ID for {@code unitType}. */
    public boolean hasUnit(String unitType) {
        return units.containsKey(unitType);
    }

    /**
     * Returns the unit ID for the given {@code unitType}.
     *
     * @throws IllegalArgumentException if the unit type is absent – callers
     *         should guard with {@link #hasUnit} first
     */
    public String getUnitId(String unitType) {
        String id = units.get(unitType);
        if (id == null) {
            throw new IllegalArgumentException(
                    "EvaluationContext does not contain unitType: " + unitType);
        }
        return id;
    }
}
