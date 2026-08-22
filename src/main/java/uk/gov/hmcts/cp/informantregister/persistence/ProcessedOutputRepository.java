package uk.gov.hmcts.cp.informantregister.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The per-authority half of the processed log: what was sent to whom, and how it went.
 *
 * <p>The request-level log answers "has this request been dealt with"; this one answers "has
 * <em>this authority's</em> document already gone", which is the only question that makes a
 * redelivery safe. {@code add-informant-register} is not idempotent — a second POST creates a second
 * register row — so partial progress has to be remembered per authority or a redelivery after three
 * of five authorities would re-send the three that succeeded.
 *
 * <p>Written in the same idiom as {@link ProcessedRequestRepository}, and for the same reason:
 * hand-written SQL, one method per statement, and <strong>the affected-row count is the
 * decision</strong>. In particular the claim and the skip are one statement, not a read followed by a
 * write. Two deliveries of the same request can be in flight at once, and a
 * {@code SELECT status} that came back {@code PENDING} would already be stale by the time the caller
 * acted on it; a conditional upsert cannot be, because the database decides.
 *
 * <p>Every timestamp comes from the database, so no row's age depends on how well two pods' clocks
 * agree.
 */
public class ProcessedOutputRepository {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String AUTHORITY = "authority";

    /**
     * Statement 1 — claim this authority for a POST, or discover it has already been posted.
     *
     * <p>The row is written <em>before</em> the POST so that an ambiguous outcome — a timeout, a
     * dropped connection — still leaves evidence that something was attempted and what was in it.
     *
     * <p>The {@code DO UPDATE ... WHERE status <> 'POSTED'} is the skip rule of the design rules
     * expressed as a predicate rather than as a branch in Java: a conflicting row that is already
     * POSTED matches nothing, so the statement affects no rows and the caller is told, in the same
     * breath, both that the row exists and that it must not send again. A row in any other state —
     * PENDING left by a crash, FAILED left by a rejection — is re-claimed and its digest replaced,
     * because the digest describes the body that is about to be sent.
     *
     * <p>{@code output_id} is supplied by the caller and survives a re-claim untouched: the conflict
     * branch leaves it alone, so the identity of an output row is fixed the first time it is written.
     */
    private static final String CLAIM_PENDING = """
            INSERT INTO processed_output (
                output_id, source, request_id, prosecution_authority_id, status, request_digest,
                created_at, updated_at)
            VALUES (
                :outputId, :source, :requestId, :authority, 'PENDING', :digest,
                now(), now())
            ON CONFLICT (source, request_id, prosecution_authority_id) DO UPDATE
               SET status = 'PENDING',
                   request_digest = EXCLUDED.request_digest,
                   updated_at = now()
             WHERE processed_output.status <> 'POSTED'
            """;

    /** Statement 2 — the POST was accepted. */
    private static final String RECORD_POSTED = """
            UPDATE processed_output
               SET status = 'POSTED', updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND prosecution_authority_id = :authority
            """;

    /**
     * Statement 3 — the POST did not succeed, however it failed.
     *
     * <p>{@code request_digest} is deliberately left in place. What was attempted is the
     * reconciliation evidence, and it is worth more after a failure than after a success.
     */
    private static final String RECORD_FAILED = """
            UPDATE processed_output
               SET status = 'FAILED', updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND prosecution_authority_id = :authority
            """;

    private final JdbcClient jdbcClient;

    /**
     * Creates the repository over the processed log's connection.
     *
     * @param jdbcClient the processed log's connection
     */
    public ProcessedOutputRepository(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Statement 1 — claim this authority for submission, writing the row the POST will be judged by.
     *
     * @param outputId               the identifier for a row written for the first time; ignored when
     *                               the row already exists
     * @param source                 the request's key, part 1
     * @param requestId              the request's key, part 2
     * @param prosecutionAuthorityId the authority this output is for
     * @param requestDigest          SHA-256 of the body about to be sent
     * @return whether this delivery may POST. False means the authority is already POSTED and is
     *         skipped, which is how partial progress survives a redelivery or a replay.
     */
    public boolean claimPending(
            final UUID outputId,
            final String source,
            final UUID requestId,
            final String prosecutionAuthorityId,
            final String requestDigest) {
        return affected(jdbcClient.sql(CLAIM_PENDING)
                .param("outputId", outputId)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .param(AUTHORITY, prosecutionAuthorityId)
                .param("digest", requestDigest)
                .update());
    }

    /**
     * Statement 2 — record that this authority's document was accepted.
     *
     * @param source                 the request's key, part 1
     * @param requestId              the request's key, part 2
     * @param prosecutionAuthorityId the authority this output is for
     * @return whether a row was updated; false means no row was ever claimed for this authority
     */
    public boolean recordPosted(
            final String source, final UUID requestId, final String prosecutionAuthorityId) {
        return affected(outcome(RECORD_POSTED, source, requestId, prosecutionAuthorityId));
    }

    /**
     * Statement 3 — record that this authority's document did not go.
     *
     * @param source                 the request's key, part 1
     * @param requestId              the request's key, part 2
     * @param prosecutionAuthorityId the authority this output is for
     * @return whether a row was updated; false means no row was ever claimed for this authority
     */
    public boolean recordFailed(
            final String source, final UUID requestId, final String prosecutionAuthorityId) {
        return affected(outcome(RECORD_FAILED, source, requestId, prosecutionAuthorityId));
    }

    /** The two outcome writes differ only in the status they set; the key predicate is common. */
    private int outcome(
            final String sql,
            final String source,
            final UUID requestId,
            final String prosecutionAuthorityId) {
        return jdbcClient.sql(sql)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .param(AUTHORITY, prosecutionAuthorityId)
                .update();
    }

    private static boolean affected(final int rows) {
        return rows > 0;
    }
}
