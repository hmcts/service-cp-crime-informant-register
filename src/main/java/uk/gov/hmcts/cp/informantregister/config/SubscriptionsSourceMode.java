package uk.gov.hmcts.cp.informantregister.config;

/**
 * Which now-subscriptions source the service runs with.
 *
 * <p>A setting rather than a Spring profile, for the same reason
 * {@link PayloadSourceMode} is one: the choice has to be stated, not inherited. "Which adapter is
 * deployed" is a question an operator must be able to answer from configuration rather than from the
 * profile list.
 */
public enum SubscriptionsSourceMode {

    /** The reference-data query-API adapter. The deployed value, and the default. */
    LIVE,

    /**
     * The refusing stub, which answers no query and reports that it cannot.
     *
     * <p>For local runs and for the container suites whose subject is settlement and the processed
     * log rather than the register's recipients, so they need no reference-data server to exist.
     * Deliberately a refusal and not an empty answer: an empty answer is a legitimate business
     * outcome, so a stub that gave one would let a real hearing produce a real register addressed to
     * nobody and record it COMPLETED. See
     * {@link uk.gov.hmcts.cp.informantregister.adapter.stub.RefusingNowSubscriptionsSource}.
     */
    STUB
}
