package j143.github.citrus;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic traffic-bucketing utility.
 *
 * <p>Maps a {@code (unitId, experimentName)} pair to an integer bucket in the
 * range {@code [0, 9999]}, giving 0.01 % rollout granularity.
 *
 * <p>Invariant: the same pair always maps to the same bucket, regardless of
 * JVM restart, locale, or hash-seed changes in the platform. This is
 * guaranteed by using Guava's {@code murmur3_32_fixed} with a hard-coded seed.
 *
 * <p><strong>Why not {@code String.hashCode()}?</strong> Java's
 * {@code String.hashCode()} implementation is not specified to be stable
 * across JVM versions and must not be used for persistent bucketing.
 */
public final class ExperimentHasher {

    /** Arbitrary prime used as the MurmurHash3 seed. Must never change. */
    static final int HASH_SEED = 104729;

    private ExperimentHasher() {}

    /**
     * Returns a deterministic bucket in the range {@code [0, 9999]} for the
     * given unit and experiment.
     *
     * @param unitId         identifier of the unit being evaluated (e.g. driver UUID)
     * @param experimentName name of the experiment (must be stable across deployments)
     * @return bucket index in {@code [0, 9999]}
     */
    public static int getBucket(String unitId, String experimentName) {
        String key = unitId + "." + experimentName;
        int hash = Hashing.murmur3_32_fixed(HASH_SEED)
                          .hashString(key, StandardCharsets.UTF_8)
                          .asInt();
        // Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (negative!).
        // Mask the sign bit instead to guarantee a non-negative result.
        return (hash & 0x7fff_ffff) % 10_000;
    }
}
