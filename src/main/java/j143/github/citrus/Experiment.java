package j143.github.citrus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable model for a single A/B or multivariate experiment.
 *
 * <p>An experiment owns:
 * <ul>
 *   <li>A set of traffic splits that map variant names to bucket counts
 *       (must total {@code 10_000}).</li>
 *   <li>A parameter-override map: {@code paramKey → (variant → value)}.
 *       Only the parameters listed here are controlled by this experiment.</li>
 * </ul>
 *
 * <p>Instances are constructed via {@link Builder} and are safe to share across
 * threads after construction.
 */
public final class Experiment {

    private final String  name;
    private final boolean enabled;
    private final String  unitType;

    /**
     * Ordered list of bucket-range upper bounds (exclusive) and their variant names.
     * Sorted ascending by {@code upperBound}. The last entry's {@code upperBound}
     * should equal {@code 10_000}.
     */
    private final List<BucketRange> bucketRanges;

    /** paramKey → (variant → raw value from YAML) */
    private final Map<String, Map<String, Object>> parameterOverrides;

    private Experiment(Builder b) {
        this.name               = b.name;
        this.enabled            = b.enabled;
        this.unitType           = b.unitType;
        this.bucketRanges       = Collections.unmodifiableList(new ArrayList<>(b.bucketRanges));
        this.parameterOverrides = Collections.unmodifiableMap(deepCopy(b.parameterOverrides));
    }

    public String  getName()     { return name;     }
    public boolean isEnabled()   { return enabled;  }
    public String  getUnitType() { return unitType; }

    /** Returns the set of parameter keys overridden by this experiment. */
    public Set<String> getParameterKeys() {
        return parameterOverrides.keySet();
    }

    /**
     * Maps a {@code bucket} index ({@code [0, 9_999]}) to the name of the
     * variant that owns that bucket.
     *
     * @throws IllegalArgumentException if the bucket falls outside all ranges
     *         (indicates a misconfigured experiment whose splits do not sum to 10 000)
     */
    public String getVariantForBucket(int bucket) {
        for (BucketRange range : bucketRanges) {
            if (bucket < range.upperBound) {
                return range.variant;
            }
        }
        throw new IllegalArgumentException(
                "Bucket " + bucket + " outside defined ranges for experiment: " + name);
    }

    /**
     * Returns the integer override for {@code paramKey} / {@code variant}, or
     * {@code null} if no override is defined.
     */
    public Integer getIntOverride(String paramKey, String variant) {
        Object raw = getOverride(paramKey, variant);
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).intValue();
        return Integer.parseInt(raw.toString());
    }

    /**
     * Returns the long override for {@code paramKey} / {@code variant}, or
     * {@code null} if no override is defined.
     */
    public Long getLongOverride(String paramKey, String variant) {
        Object raw = getOverride(paramKey, variant);
        if (raw == null) return null;
        if (raw instanceof Number) return ((Number) raw).longValue();
        return Long.parseLong(raw.toString());
    }

    private Object getOverride(String paramKey, String variant) {
        Map<String, Object> variants = parameterOverrides.get(paramKey);
        if (variants == null) return null;
        return variants.get(variant);
    }

    // -----------------------------------------------------------------------

    private static Map<String, Map<String, Object>> deepCopy(
            Map<String, Map<String, Object>> src) {
        Map<String, Map<String, Object>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
        }
        return copy;
    }

    // -----------------------------------------------------------------------

    static final class BucketRange {
        final int    upperBound; // exclusive
        final String variant;

        BucketRange(int upperBound, String variant) {
            this.upperBound = upperBound;
            this.variant    = variant;
        }
    }

    // -----------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String  name;
        private boolean enabled;
        private String  unitType;
        private final List<BucketRange>                    bucketRanges       = new ArrayList<>();
        private final Map<String, Map<String, Object>> parameterOverrides = new LinkedHashMap<>();

        public Builder name(String name)         { this.name     = name;    return this; }
        public Builder enabled(boolean enabled)  { this.enabled  = enabled; return this; }
        public Builder unitType(String unitType) { this.unitType = unitType; return this; }

        /**
         * Adds a variant and its exclusive upper bucket bound.
         * Ranges must be added in ascending order of {@code upperBound}.
         */
        public Builder addBucketRange(int upperBound, String variant) {
            bucketRanges.add(new BucketRange(upperBound, variant));
            return this;
        }

        /** Adds a parameter override entry. */
        public Builder addParameterOverride(String paramKey, Map<String, Object> variantValues) {
            parameterOverrides.put(paramKey, new HashMap<>(variantValues));
            return this;
        }

        public Experiment build() {
            return new Experiment(this);
        }
    }
}
