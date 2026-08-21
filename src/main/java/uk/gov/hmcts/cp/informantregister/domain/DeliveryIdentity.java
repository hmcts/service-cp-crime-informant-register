package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Who is delivering, and under which broker identity.
 *
 * <p>The two travel together because the guard needs both and they are both strings: the message
 * identity decides the FAILED branch (a replay under a fresh identity, or a redelivery of the one
 * that exhausted the retries), and the claim owner is stamped on the row for the run's lifetime.
 * Passing them as one value is what stops them being passed the wrong way round.
 *
 * @param messageId   the broker's messageId for this delivery
 * @param claimOwner  this runner's identity — instance and delivery — recorded in {@code claim_owner}
 */
public record DeliveryIdentity(String messageId, String claimOwner) {
}
