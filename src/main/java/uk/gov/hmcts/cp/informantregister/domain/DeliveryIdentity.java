package uk.gov.hmcts.cp.informantregister.domain;

import java.time.Instant;

/**
 * Who is delivering, and the broker facts the guard decides with.
 *
 * <p>The identity that decides the {@code FAILED} branch is the broker's own
 * <strong>sequence number</strong> — assigned on accept, unique within the queue, unchanged across a
 * redelivery of the same stored message — not {@code messageId}, which a support resubmission is free
 * to leave unchanged (spec 001-replay-by-sequence-number, FR-003/FR-004). {@code messageId} is kept
 * for the audit trail only.
 *
 * <p>{@code deliveryCount} and {@code enqueueTime} matter only for a {@code FAILED} request with no
 * recorded parking sequence number — a row parked before this feature, or by an older consumer mid
 * rollout — where the guard falls back to a heuristic over them (FR-005). {@code enqueueTime} is
 * {@code null} when the broker's annotation was absent; that is recorded as absent, never defaulted.
 *
 * <p>Whether this is the last delivery the queue permits travels with them, because it is a third,
 * independent fact about the delivery: the record cannot tell you it, and the run that fails has to
 * know it before it writes its outcome. A failure with deliveries remaining is recorded RETRYING and
 * handed back; the same failure on the final permitted delivery is recorded FAILED and parked, in the
 * transaction that stamps the delivery's sequence number onto the row.
 *
 * @param messageId              the broker's messageId for this delivery, kept for audit only
 * @param claimOwner             this runner's identity — instance and delivery — recorded in
 *                               {@code claim_owner}
 * @param finalPermittedDelivery whether the queue's delivery budget ends with this delivery
 * @param sequenceNumber         the broker's own identity for this delivery; the FAILED-branch decision
 * @param deliveryCount          the broker's delivery count for this message; consulted only for a
 *                               legacy FAILED row with no recorded parking sequence number
 * @param enqueueTime            when the broker accepted this delivery, or {@code null} when the
 *                               annotation was absent; consulted only for the same legacy case
 */
public record DeliveryIdentity(
        String messageId,
        String claimOwner,
        boolean finalPermittedDelivery,
        long sequenceNumber,
        long deliveryCount,
        Instant enqueueTime) {

    /**
     * A delivery with retries still to come, for callers with no opinion on the budget.
     *
     * @see #DeliveryIdentity(String, String, boolean)
     */
    public DeliveryIdentity(final String messageId, final String claimOwner) {
        this(messageId, claimOwner, false);
    }

    /**
     * A delivery with no broker facts of its own to supply — every caller outside the inbound
     * adapter and this feature's own suites.
     *
     * <p>{@code sequenceNumber} is derived from {@code messageId} rather than fixed, so that two
     * calls with different {@code messageId}s still look like two different deliveries to the
     * FAILED-branch decision, exactly as relying on {@code messageId} alone did before this feature —
     * and two calls with the <em>same</em> {@code messageId} (a deliberate redelivery in a test) still
     * collide, as they did before. The inbound adapter never takes this path: it always supplies the
     * broker's own sequence number, read once per delivery (see
     * {@code InformantRegisterMessageListener}).
     */
    public DeliveryIdentity(
            final String messageId, final String claimOwner, final boolean finalPermittedDelivery) {
        this(messageId, claimOwner, finalPermittedDelivery, messageId.hashCode(), 0L, null);
    }
}
