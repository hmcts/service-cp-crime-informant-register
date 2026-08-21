package uk.gov.hmcts.cp.informantregister.domain;

import java.util.UUID;

/**
 * The single-runner claim a delivery holds while its pipeline run is in flight.
 *
 * <p>Carries everything an outcome write needs to prove it may write: the key it settles, and the
 * owner and token it acquired the claim under. Every outcome write is predicated on both, so a
 * runner whose claim was reclaimed while it was working writes nothing — it discards its result and
 * abandons the delivery (data-model invariant 7).
 *
 * <p>The token is minted fresh on <em>every</em> acquisition. Owner alone is never an acquisition
 * condition: a re-acquire path predicated on the owner would let one runner increment
 * {@code attempts} twice for a single run.
 *
 * <p>There is no expiry here. Claim liveness is decided by the database, comparing
 * {@code claim_expires_at} against {@code now()} inside the conditional update — never by comparing a
 * JVM clock reading against a stored timestamp (data-model invariant 9).
 *
 * @param source    the record's key, part 1
 * @param requestId the record's key, part 2
 * @param owner     the runner identity stamped in {@code claim_owner}
 * @param token     the token minted for this acquisition
 */
public record RunClaim(String source, UUID requestId, String owner, UUID token) {
}
