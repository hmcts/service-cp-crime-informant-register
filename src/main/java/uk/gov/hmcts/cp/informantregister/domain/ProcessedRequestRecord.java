package uk.gov.hmcts.cp.informantregister.domain;

import java.time.Instant;

/**
 * What the guard reads back about a request it has seen before.
 *
 * <p>The projection of {@code processed_request} the read-and-branch step selects — the state the
 * branch decision needs, and nothing else.
 *
 * <p>The two claim columns are carried for the log line, not for the decision. Whether a claim is
 * still live is decided inside the conditional update, by the database comparing
 * {@code claim_expires_at} against its own {@code now()}; a guard that decided liveness here would be
 * comparing a JVM clock reading against a stored timestamp, which is exactly the multi-node skew the
 * data model's single-time-authority rule exists to rule out.
 *
 * @param status              the record's current state
 * @param fingerprint         the fingerprint written when the record was created; the collision comparison
 * @param failureReason       the last recorded failure reason code, or {@code null}
 * @param exhaustedMessageId  the identity of the delivery that exhausted the retries, or {@code null}
 * @param attempts            the lifetime count of pipeline-run starts
 * @param claimOwner          the current claim's owner, or {@code null} when no claim is held
 * @param claimExpiresAt      the current claim's expiry, or {@code null} when no claim is held
 */
public record ProcessedRequestRecord(
        RequestStatus status,
        String fingerprint,
        String failureReason,
        String exhaustedMessageId,
        int attempts,
        String claimOwner,
        Instant claimExpiresAt) {
}
