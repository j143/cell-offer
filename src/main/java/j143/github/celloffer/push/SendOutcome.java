package j143.github.celloffer.push;

/** Outcome of gateway acceptance, not acknowledgement by a driver. */
public enum SendOutcome {
    ACCEPTED,
    REJECTED,
    RETRYABLE_FAILURE,
    AMBIGUOUS_FAILURE,
    EXPIRED,
    INVALID
}
