package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;

/**
 * The processed log's statements, one method per statement, written out by hand.
 *
 * <p>Hand-written SQL rather than an ORM because the guard's correctness rests on what each statement
 * affects: <strong>the affected-row count is the decision</strong>. One row from the insert means this
 * delivery owns a fresh request; one row from the reclaim means it won the race for an abandoned
 * claim; one row from an outcome write means the claim it is settling is still its own. A session
 * cache and dirty-checking would sit between the code and exactly the property under test.
 *
 * <p>Zero rows is never a signal to look again. Per the data model's no-spin rule the caller hands the
 * delivery back and lets broker redelivery re-enter the state machine, which already carries back-off
 * and a delivery budget; a re-read loop would burn CPU holding a lock and could starve the runner that
 * won.
 *
 * <p>Every timestamp in these statements comes from the database. Expiry is written as
 * {@code now() + lease} and compared against {@code now()}, both inside the database, so no claim
 * decision anywhere depends on how well two pods' clocks agree.
 *
 * <p>The lease is bound as an ISO-8601 duration and cast, which is what lets a {@link Duration} reach
 * an {@code interval} parameter unambiguously; the arithmetic itself stays in SQL.
 */
public class ProcessedRequestRepository {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String OWNER = "owner";
    private static final String TOKEN = "token";
    private static final String LEASE_PARAM = "lease";
    private static final String MESSAGE_ID = "messageId";
    private static final String REASON = "reason";

    /** Statement 1 — the record, its claim and its first attempt, in one statement. */
    private static final String INSERT_NEW = """
            INSERT INTO processed_request (
                source, request_id, hearing_id, hearing_day, shared_time, event_type,
                request_fingerprint, status, attempts,
                claim_owner, claim_token, claim_expires_at,
                created_at, updated_at)
            VALUES (
                :source, :requestId, :hearingId, :hearingDay, :sharedTime, :eventType,
                :fingerprint, 'RECEIVED', 1,
                :owner, :token, now() + CAST(:lease AS interval),
                now(), now())
            ON CONFLICT (source, request_id) DO NOTHING
            """;

    /**
     * Statement 2 — everything the branch decision needs.
     *
     * <p>{@code failure_reason} is read alongside the data model's column list so the replay can
     * carry the reason the record was parked into its audit note before the transition clears the
     * column. It informs no decision.
     */
    private static final String READ_RECORD = """
            SELECT status, request_fingerprint, failure_reason, exhausted_message_id,
                   attempts, claim_owner, claim_expires_at
              FROM processed_request
             WHERE source = :source AND request_id = :requestId
            """;

    /** Statement 3 — take over a claim that is absent or past its expiry. */
    private static final String RECLAIM_STALE_CLAIM = """
            UPDATE processed_request
               SET claim_owner = :owner,
                   claim_token = :token,
                   claim_expires_at = now() + CAST(:lease AS interval),
                   attempts = attempts + 1,
                   updated_at = now()
             WHERE source = :source
               AND request_id = :requestId
               AND status IN ('RECEIVED', 'RETRYING')
               AND (claim_expires_at IS NULL OR claim_expires_at < now())
            """;

    /** Statement 4 — the run succeeded. */
    private static final String RECORD_COMPLETED = """
            UPDATE processed_request
               SET status = 'COMPLETED', completion_reason = :reason,
                   claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
                   updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND claim_owner = :owner AND claim_token = :token
            """;

    /** Statement 4 — the run failed, and deliveries of this message remain. */
    private static final String RECORD_RETRYING = """
            UPDATE processed_request
               SET status = 'RETRYING', failure_reason = :reason,
                   claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
                   updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND claim_owner = :owner AND claim_token = :token
            """;

    /** Statement 4 — the run failed on the final permitted delivery. */
    private static final String RECORD_FAILED = """
            UPDATE processed_request
               SET status = 'FAILED', failure_reason = :reason,
                   exhausted_message_id = :messageId,
                   claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
                   updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND claim_owner = :owner AND claim_token = :token
            """;

    /**
     * Statement 5 — a deliberate resubmission under a fresh identity.
     *
     * <p>{@code attempts} is carried forward, not reset: the counter is a lifetime tally, so five
     * failed deliveries followed by a successful replay leave the row showing 6. The
     * {@code exhausted_message_id <> :messageId} predicate is defence in depth — the same-identity
     * case is already decided on the read — and is safe against NULL because the FAILED check
     * guarantees the column is populated on every parked row.
     */
    private static final String REPLAY_FAILED = """
            UPDATE processed_request
               SET status = 'RECEIVED',
                   claim_owner = :owner, claim_token = :token,
                   claim_expires_at = now() + CAST(:lease AS interval),
                   attempts = attempts + 1,
                   failure_reason = NULL,
                   exhausted_message_id = NULL,
                   audit_note = :note,
                   updated_at = now()
             WHERE source = :source
               AND request_id = :requestId
               AND status = 'FAILED'
               AND exhausted_message_id <> :messageId
            """;

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
     * @return whether this delivery created the record. False means a record already exists — this
     *         delivery lost the insert race, or the request is simply known — and the caller reads and
     *         branches.
     */
    public boolean insertNew(
            final DistributionCommand command,
            final String fingerprint,
            final RunClaim runClaim) {
        return affected(jdbcClient.sql(INSERT_NEW)
                .param(SOURCE, command.source())
                .param(REQUEST_ID, command.requestId())
                .param("hearingId", command.hearingId())
                .param("hearingDay", command.hearingDay())
                .param("sharedTime", OffsetDateTime.ofInstant(command.sharedTime(), ZoneOffset.UTC))
                .param("eventType", command.eventType())
                .param("fingerprint", fingerprint)
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param(LEASE_PARAM, lease())
                .update());
    }

    /**
     * Statement 2 — read the record the branch decision is made from.
     */
    public Optional<ProcessedRequestRecord> read(final String source, final UUID requestId) {
        return jdbcClient.sql(READ_RECORD)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNumber) -> new ProcessedRequestRecord(
                        RequestStatus.valueOf(rs.getString("status")),
                        rs.getString("request_fingerprint"),
                        rs.getString("failure_reason"),
                        rs.getString("exhausted_message_id"),
                        rs.getInt("attempts"),
                        rs.getString("claim_owner"),
                        instant(rs.getObject("claim_expires_at", OffsetDateTime.class))))
                .optional();
    }

    /**
     * Statement 3 — reclaim an absent or expired claim on a non-terminal record.
     *
     * <p>The state is deliberately left alone: the reclaim moves the claim and the attempt counter,
     * and only an outcome moves the state.
     *
     * @return whether this delivery acquired the claim. False means the claim is live, another
     *         delivery won, or the record turned terminal in between — all three are handed back.
     */
    public boolean reclaimStaleClaim(final RunClaim runClaim) {
        return affected(jdbcClient.sql(RECLAIM_STALE_CLAIM)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param(LEASE_PARAM, lease())
                .update());
    }

    /**
     * Statement 4 — record a completed run, releasing the claim.
     *
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordCompleted(final RunClaim runClaim, final String completionReason) {
        return affected(outcome(RECORD_COMPLETED, runClaim)
                .param(REASON, completionReason)
                .update());
    }

    /**
     * Statement 4 — record a transient failure, releasing the claim.
     *
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordRetrying(final RunClaim runClaim, final String failureReason) {
        return affected(outcome(RECORD_RETRYING, runClaim)
                .param(REASON, failureReason)
                .update());
    }

    /**
     * Statement 4 — park the request, recording the identity that exhausted the deliveries in the
     * same statement as the state, so a parked record can never exist without saying what parked it.
     *
     * <p>The identity is the claim's own — the delivery that acquired it is by definition the one
     * whose failure parks the request — so no caller can supply an unrelated one.
     *
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordFailed(final RunClaim runClaim, final String failureReason) {
        return affected(outcome(RECORD_FAILED, runClaim)
                .param(REASON, failureReason)
                .param(MESSAGE_ID, runClaim.messageId())
                .update());
    }

    /**
     * Statement 5 — replay a parked request under a fresh message identity, the claim's own.
     *
     * @return whether the replay was admitted. False means the record moved between the read and this
     *         update; it never means the identity was the same one, which the read decides.
     */
    public boolean replayFailed(final RunClaim runClaim, final String auditNote) {
        return affected(jdbcClient.sql(REPLAY_FAILED)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token())
                .param(LEASE_PARAM, lease())
                .param(MESSAGE_ID, runClaim.messageId())
                .param("note", auditNote)
                .update());
    }

    /** The three outcome writes differ only in what they set; the predicate is common to all. */
    private JdbcClient.StatementSpec outcome(final String sql, final RunClaim runClaim) {
        return jdbcClient.sql(sql)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(OWNER, runClaim.owner())
                .param(TOKEN, runClaim.token());
    }

    /** ISO-8601, which Postgres reads as an interval without a locale or a format assumption. */
    private String lease() {
        return claimLease.toString();
    }

    private static boolean affected(final int rows) {
        return rows > 0;
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
