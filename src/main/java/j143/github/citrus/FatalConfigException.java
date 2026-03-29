package j143.github.citrus;

/**
 * Thrown when {@link ExperimentRegistry} detects that two or more active experiments
 * attempt to override the same parameter key.
 *
 * <p>This is a fatal startup condition. Citrus-lite uses a zero-tolerance
 * conflict resolution policy: "last-applied wins" semantics are explicitly
 * rejected because they cause silent data corruption in A/B analyses where both
 * experiments assume sole ownership of the parameter.
 */
public class FatalConfigException extends RuntimeException {

    public FatalConfigException(String message) {
        super(message);
    }

    public FatalConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
