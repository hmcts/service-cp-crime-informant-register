package uk.gov.hmcts.cp.informantregister.domain;

import java.util.Set;

/**
 * The explicit, reviewed mapping a re-park uses when a {@code FAILED} row has no recorded
 * {@code parking_category} — a row parked before this feature, or by an older consumer mid-rollout
 * (spec FR-010, 001-replay-by-sequence-number).
 *
 * <p>Applied only in that absence. Where a category <em>is</em> recorded it is used as recorded and
 * never reconstructed from the stored reason — this mapping exists solely to answer for the rows
 * that predate recording one at all.
 *
 * <p>The three reasons the non-transient path can record today map to {@code NON_TRANSIENT}; every
 * other declared reason, and anything this mapping does not recognise at all, maps to
 * {@code EXHAUSTED} — the conservative answer, since an exhausted parking is the more common of the
 * two and nothing here is in a position to prove the rarer one.
 */
public final class FailureCategoryMapping {

    private static final Set<String> NON_TRANSIENT_REASONS = Set.of(
            ReasonCode.SUBMISSION_REJECTED.code(),
            ReasonCode.SUBMISSION_NOT_ACCEPTED.code(),
            ReasonCode.TRANSFORMATION_FAILED.code());

    private FailureCategoryMapping() {
        // Pure static lookup.
    }

    /**
     * The category a legacy row's stored reason maps to.
     *
     * @param storedReasonCode the text read back from {@code failure_reason}, or {@code null}
     * @return {@link DeadLetterReason#NON_TRANSIENT} for the non-transient path's own three
     *         reasons; {@link DeadLetterReason#EXHAUSTED} for everything else, {@code null} and an
     *         unrecognised reason included
     */
    public static DeadLetterReason categoryFor(final String storedReasonCode) {
        // Set.of(...).contains(null) throws; a missing stored reason is exactly the "everything
        // else" case this mapping already answers EXHAUSTED for, so it is checked first rather
        // than left to throw.
        return storedReasonCode != null && NON_TRANSIENT_REASONS.contains(storedReasonCode)
                ? DeadLetterReason.NON_TRANSIENT : DeadLetterReason.EXHAUSTED;
    }
}
