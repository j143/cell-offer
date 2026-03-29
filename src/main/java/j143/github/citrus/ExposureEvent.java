package j143.github.citrus;

/**
 * Immutable record of a single experiment exposure.
 *
 * <p>An exposure is recorded every time {@link ExperimentClient#getIntParam} or
 * {@link ExperimentClient#getLongParam} evaluates an active experiment for a unit.
 * Events are written asynchronously via {@link AsyncExposureLogger} so the calling
 * thread is never blocked.
 */
public final class ExposureEvent {

    private final long   timestamp;
    private final String unitId;
    private final String experimentName;
    private final String variant;

    public ExposureEvent(long timestamp, String unitId, String experimentName, String variant) {
        this.timestamp      = timestamp;
        this.unitId         = unitId;
        this.experimentName = experimentName;
        this.variant        = variant;
    }

    public long   getTimestamp()      { return timestamp;      }
    public String getUnitId()         { return unitId;         }
    public String getExperimentName() { return experimentName; }
    public String getVariant()        { return variant;        }

    @Override
    public String toString() {
        return String.format(
                "{\"ts\":%d,\"unitId\":\"%s\",\"exp\":\"%s\",\"variant\":\"%s\"}",
                timestamp, unitId, experimentName, variant);
    }
}
