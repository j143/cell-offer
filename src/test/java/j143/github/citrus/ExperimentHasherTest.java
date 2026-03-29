package j143.github.citrus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the deterministic, orthogonal bucketing guarantees of
 * {@link ExperimentHasher}.
 */
class ExperimentHasherTest {

    @Test
    void getBucket_isInRange() {
        for (int i = 0; i < 1000; i++) {
            int bucket = ExperimentHasher.getBucket("unit-" + i, "ExpA");
            assertThat(bucket)
                    .as("bucket for unit-%d must be in [0, 9999]", i)
                    .isBetween(0, 9_999);
        }
    }

    @Test
    void getBucket_isDeterministic() {
        String unitId = "driver-42";
        String expName = "RamenBackoffV2";
        int first  = ExperimentHasher.getBucket(unitId, expName);
        int second = ExperimentHasher.getBucket(unitId, expName);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void getBucket_differentExperimentNames_produceDifferentBuckets() {
        // Same unit, different experiment names must (with very high probability)
        // hash to different buckets, preventing allocation bias.
        String unitId = "driver-99";
        int bucketA = ExperimentHasher.getBucket(unitId, "ExperimentA");
        int bucketB = ExperimentHasher.getBucket(unitId, "ExperimentB");
        // It is theoretically possible for these to collide, but with a 1/10 000
        // chance – the test would be flaky only 0.01 % of the time.
        assertThat(bucketA).isNotEqualTo(bucketB);
    }

    @Test
    void getBucket_distributionIsRoughlyUniform() {
        // 10 000 units should each land in one of 10 000 buckets.
        // With a good hash function the occupancy should be close to 1 per bucket.
        int[] counts = new int[10_000];
        int units = 50_000;
        for (int i = 0; i < units; i++) {
            int bucket = ExperimentHasher.getBucket("driver-" + i, "TestExp");
            counts[bucket]++;
        }
        long zeroBuckets = 0;
        for (int c : counts) {
            if (c == 0) zeroBuckets++;
        }
        // With 50 000 units across 10 000 buckets we expect very few empty buckets.
        assertThat(zeroBuckets)
                .as("Too many empty buckets – hash distribution is skewed")
                .isLessThan(100);
    }

    @Test
    void getBucket_handlesIntegerMinValueWithoutNegativeResult() {
        // Regression: Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (negative).
        // We use the bitwise-AND mask instead; verify all outputs are non-negative.
        for (int i = 0; i < 10_000; i++) {
            assertThat(ExperimentHasher.getBucket("u" + i, "e" + i)).isGreaterThanOrEqualTo(0);
        }
    }
}
