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
 * <p>The delivery's message identity travels with the claim rather than being handed separately to
 * the write that parks a request. The identity recorded as having exhausted the retries has to be the
 * delivery that was running, and carrying it here makes any other identity unrepresentable instead of
 * merely wrong.
 *
 * <p>The delivery's <strong>budget position</strong> travels with it for the same reason. Whether
 * the queue will send this message again is known at admission and needed on the way out: an outcome
 * write that affects no row hands the delivery back, and a hand-back on the last delivery a message
 * is entitled to is a hand-back into nothing — the broker parks it under its own reason, with no
 * reason code of ours behind it. Carrying it here is what lets the one rejection that all four
 * outcome writes fall back to consult the budget, rather than each write being handed it separately
 * and one of them being forgotten.
 *
 * @param source    the record's key, part 1
 * @param requestId the record's key, part 2
 * @param owner     the runner identity stamped in {@code claim_owner}
 * @param token     the token minted for this acquisition
 * @param messageId the broker identity of the delivery that acquired this claim
 * @param finalPermittedDelivery whether the queue will deliver this message again after this run
 */
public record RunClaim(
        String source,
        UUID requestId,
        String owner,
        UUID token,
        String messageId,
        boolean finalPermittedDelivery) {

    /**
     * A claim held by a delivery with retries still to come.
     *
     * <p>The ordinary case, and the one most of the guard's behaviour is indifferent to: what an
     * outcome write records is decided the same way whether or not this is the last chance — the
     * budget changes only what happens when the write finds the claim gone. A caller with no opinion
     * does not have to invent one, exactly as with
     * {@link DeliveryIdentity#DeliveryIdentity(String, String)}.
     *
     * @param source    the record's key, part 1
     * @param requestId the record's key, part 2
     * @param owner     the runner identity stamped in {@code claim_owner}
     * @param token     the token minted for this acquisition
     * @param messageId the broker identity of the delivery that acquired this claim
     */
    public RunClaim(final String source, final UUID requestId, final String owner,
            final UUID token, final String messageId) {
        this(source, requestId, owner, token, messageId, false);
    }
}
