package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;

/**
 * The processed log's statements, one method per statement.
 *
 * <p>Seam only at this task: every method throws, so each guard test fails on the behaviour it is
 * about rather than on a stub that quietly returns something plausible.
 */
public class ProcessedRequestRepository {

    private final JdbcClient jdbcClient;
    private final Duration claimLease;

    /**
     * @param jdbcClient the processed log's connection
     * @param claimLease {@code informantregister.claim.lease} — how long an acquired claim stays
     *                   live. Passed as the one value the statements bind rather than as the whole
     *                   configuration tree: the lease is all the SQL needs, and the expiry it
     *                   produces is computed by the database, not here.
     */
    public ProcessedRequestRepository(final JdbcClient jdbcClient, final Duration claimLease) {
        this.jdbcClient = jdbcClient;
        this.claimLease = claimLease;
    }

    /**
     * Statement 1 — insert a new request, taking the claim and the first attempt in one statement.
     *
     * @return whether this delivery created the record
     */
    public boolean insertNew(
            final DistributionCommand command,
            final String fingerprint,
            final RunClaim runClaim) {
        throw new UnsupportedOperationException("T021 implements the processed-log statements");
    }

    /**
     * Statement 2 — read the record the branch decision is made from.
     */
    public Optional<ProcessedRequestRecord> read(final String source, final UUID requestId) {
        throw new UnsupportedOperationException("T021 implements the processed-log statements");
    }

    /**
     * Statement 3 — reclaim an absent or expired claim on a non-terminal record.
     *
     * @return whether this delivery acquired the claim
     */
    public boolean reclaimStaleClaim(final RunClaim runClaim) {
        throw new UnsupportedOperationException("T021 implements the processed-log statements");
    }

    /**
     * Statement 4 — record a completed run, releasing the claim.
     *
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordCompleted(final RunClaim runClaim, final String completionReason) {
        throw new UnsupportedOperationException("T021 implements the processed-log statements");
    }

    /**
     * Statement 4 — record a transient failure, releasing the claim.
     *
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordRetrying(final RunClaim runClaim, final String failureReason) {
        throw new UnsupportedOperationException("T021 implements the processed-log statements");
    }

    /**
     * Statement 4 — park the request, recording the identity that exhausted the deliveries in the
     * same statement as the state.
     *
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordFailed(
            final RunClaim runClaim,
            final String failureReason,
            final String exhaustedMessageId) {
        throw new UnsupportedOperationException("T021 implements the processed-log statements");
    }

    /**
     * Statement 5 — replay a parked request under a fresh message identity.
     *
     * @return whether the replay was admitted
     */
    public boolean replayFailed(
            final RunClaim runClaim,
            final String messageId,
            final String auditNote) {
        throw new UnsupportedOperationException("T021 implements the processed-log statements");
    }
}
