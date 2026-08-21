package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Who is delivering, and under which broker identity.
 *
 * <p>The two travel together because the guard needs both and they are both strings: the message
 * identity decides the FAILED branch (a replay under a fresh identity, or a redelivery of the one
 * that exhausted the retries), and the claim owner is stamped on the row for the run's lifetime.
 * Passing them as one value is what stops them being passed the wrong way round.
 *
 * <p>Whether this is the last delivery the queue permits travels with them, because it is the third
 * fact about the delivery rather than about the request: the record cannot tell you it, and the run
 * that fails has to know it before it writes its outcome. A failure with deliveries remaining is
 * recorded RETRYING and handed back; the same failure on the final permitted delivery is recorded
 * FAILED and parked, in the transaction that stamps the identity above onto the row.
 *
 * @param messageId              the broker's messageId for this delivery
 * @param claimOwner             this runner's identity — instance and delivery — recorded in
 *                               {@code claim_owner}
 * @param finalPermittedDelivery whether the queue's delivery budget ends with this delivery
 */
public record DeliveryIdentity(String messageId, String claimOwner, boolean finalPermittedDelivery) {

    /**
     * A delivery with retries still to come.
     *
     * <p>The ordinary case, and the one the guard's own suites care about: everything the processed
     * log decides — the claim, the collision, the replay — is decided the same way whether or not
     * this is the last chance, so a caller that has no opinion does not have to invent one.
     */
    public DeliveryIdentity(final String messageId, final String claimOwner) {
        this(messageId, claimOwner, false);
    }
}
