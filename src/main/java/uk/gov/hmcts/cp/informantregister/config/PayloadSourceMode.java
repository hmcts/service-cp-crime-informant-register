package uk.gov.hmcts.cp.informantregister.config;

/**
 * Which payload source the service runs with.
 *
 * <p>A setting rather than a Spring profile because the choice has to be stated, not inherited. The
 * skeleton's stub was selected by the absence of anything else; once a real adapter exists, "which
 * one is deployed" is a question an operator must be able to answer from configuration rather than
 * from the profile list.
 */
public enum PayloadSourceMode {

    /** The cache-with-query-fallback adapter. The deployed value, and the default. */
    LIVE,

    /**
     * The logging no-op that fetches nothing.
     *
     * <p>For local runs and for the container suites whose subject is settlement and the processed
     * log rather than the payload, so they need neither a cache nor a query side to exist.
     */
    STUB
}
