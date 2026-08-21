package uk.gov.hmcts.cp.informantregister.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedRequestRepository;

/**
 * What every guard suite needs: a pooled connection to the shared Postgres, a guard built over it,
 * and a way to read a whole row back.
 *
 * <p>The pool is real (Hikari, several connections) rather than a single-connection helper, because
 * the contention and reclamation suites run genuinely concurrent deliveries and a serialising
 * connection would quietly turn those races into a queue.
 *
 * <p>Every suite mints fresh identifiers through {@link #command()}, so the suites share one
 * container without sharing rows and none of them needs to truncate a table another might be using.
 */
public final class ProcessedLogTestSupport {

    /** The single value {@code source} is permitted to take. */
    public static final String SOURCE = "RESULTS";

    private static DataSource dataSource;

    private ProcessedLogTestSupport() {
        // Static fixture holder.
    }

    /**
     * The shared, migrated data source. Started and migrated on first use.
     */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            PostgresTestSupport.applyFlyway();
            dataSource = DataSourceBuilder.create()
                    .url(PostgresTestSupport.jdbcUrl())
                    .username(PostgresTestSupport.username())
                    .password(PostgresTestSupport.password())
                    .build();
        }
        return dataSource;
    }

    public static JdbcClient jdbcClient() {
        return JdbcClient.create(dataSource());
    }

    /**
     * A repository holding the claim lease a suite wants.
     */
    public static ProcessedRequestRepository repository(final Duration lease) {
        return new ProcessedRequestRepository(jdbcClient(), lease);
    }

    /**
     * Ages an existing claim until it is unambiguously past its expiry — what a crashed runner leaves
     * behind — using the database's own clock and without sleeping.
     *
     * <p>An hour into the past rather than a lease of zero. Zero writes an expiry of {@code now()},
     * and the reclaim requires {@code claim_expires_at < now()} strictly, so two transactions landing
     * on the same microsecond would leave the claim un-reclaimable and the suite flaky for reasons
     * that have nothing to do with the guard.
     *
     * <p>{@code updated_at} is deliberately left alone: this is a fixture ageing a claim, not the
     * service recording a change.
     *
     * @throws IllegalStateException if there was no claim to age — a fixture that quietly does
     *                               nothing would turn every test using it green for the wrong reason
     */
    public static void expireClaim(final String source, final UUID requestId) {
        final int aged = jdbcClient()
                .sql("""
                        UPDATE processed_request
                           SET claim_expires_at = now() - interval '1 hour'
                         WHERE source = :source AND request_id = :requestId
                           AND claim_owner IS NOT NULL
                        """)
                .param("source", source)
                .param("requestId", requestId)
                .update();
        if (aged != 1) {
            throw new IllegalStateException(
                    "expected one live claim to age for " + source + "/" + requestId + ", aged " + aged);
        }
    }

    public static IdempotencyGuard guard(final Duration lease, final ProcessingMetrics metrics) {
        return new IdempotencyGuard(repository(lease), metrics);
    }

    /**
     * A guard whose instruments nothing asserts on.
     */
    public static IdempotencyGuard guard(final Duration lease) {
        return guard(lease, new ProcessingMetrics(new SimpleMeterRegistry()));
    }

    /**
     * A fresh, valid command — a request no other test has seen.
     */
    public static DistributionCommand command() {
        return new DistributionCommand(
                SOURCE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 20),
                Instant.parse("2026-08-20T09:00:00Z"),
                "Hearing_Resulted");
    }

    /**
     * Every column of a processed-request row, so a test can assert that nothing at all changed.
     */
    public record Row(
            String source,
            UUID requestId,
            UUID hearingId,
            LocalDate hearingDay,
            Instant sharedTime,
            String eventType,
            String requestFingerprint,
            String status,
            int attempts,
            String completionReason,
            String failureReason,
            String exhaustedMessageId,
            String auditNote,
            String claimOwner,
            UUID claimToken,
            Instant claimExpiresAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * Reads a row back in full, or reports its absence.
     */
    public static Optional<Row> row(final String source, final UUID requestId) {
        return row(jdbcClient(), source, requestId);
    }

    /**
     * The same read against a caller-supplied connection — for the durability suite, which owns its
     * own container and rebuilds its pool underneath itself.
     */
    public static Optional<Row> row(
            final JdbcClient client, final String source, final UUID requestId) {
        return client
                .sql("""
                        SELECT source, request_id, hearing_id, hearing_day, shared_time, event_type,
                               request_fingerprint, status, attempts, completion_reason, failure_reason,
                               exhausted_message_id, audit_note, claim_owner, claim_token,
                               claim_expires_at, created_at, updated_at
                          FROM processed_request
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", source)
                .param("requestId", requestId)
                .query((rs, rowNumber) -> new Row(
                        rs.getString("source"),
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("hearing_id", UUID.class),
                        rs.getObject("hearing_day", LocalDate.class),
                        instant(rs.getObject("shared_time", OffsetDateTime.class)),
                        rs.getString("event_type"),
                        rs.getString("request_fingerprint"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("completion_reason"),
                        rs.getString("failure_reason"),
                        rs.getString("exhausted_message_id"),
                        rs.getString("audit_note"),
                        rs.getString("claim_owner"),
                        rs.getObject("claim_token", UUID.class),
                        instant(rs.getObject("claim_expires_at", OffsetDateTime.class)),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional();
    }

    /**
     * The row, insisting it exists — the ordinary case in a suite that has just written one.
     */
    public static Row requireRow(final String source, final UUID requestId) {
        return requireRow(jdbcClient(), source, requestId);
    }

    /**
     * The row, insisting it exists, against a caller-supplied connection.
     */
    public static Row requireRow(
            final JdbcClient client, final String source, final UUID requestId) {
        return row(client, source, requestId).orElseThrow(() ->
                new IllegalStateException("no processed_request row for " + source + "/" + requestId));
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
