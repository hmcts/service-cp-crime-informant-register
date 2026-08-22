package uk.gov.hmcts.cp.informantregister.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;

/**
 * The per-authority half of the processed log, against a real Postgres.
 *
 * <p>These statements are what makes an at-least-once delivery safe to submit from. The property
 * under test throughout is the affected-row count, exactly as it is for the request-level
 * repository: a claim that affects no rows means this authority has already been posted and must not
 * be posted again, and no amount of reading the row afterwards would make that decision safe under
 * two concurrent deliveries.
 *
 * <p>Every case seeds its own {@code processed_request} parent, because {@code processed_output}
 * carries a foreign key to it. That is the schema saying what the design rules say: an output row is
 * evidence about a request, and evidence with nothing to be about is not evidence.
 */
@DisplayName("processed_output repository")
class ProcessedOutputRepositoryIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String AUTHORITY = "PA-0001";
    private static final String OTHER_AUTHORITY = "PA-0002";
    private static final String DIGEST =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String OTHER_DIGEST =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    private static ProcessedOutputRepository repository() {
        return new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient());
    }

    /** Seeds a request row so the output rows have the parent their foreign key requires. */
    private static DistributionCommand seededRequest() {
        final DistributionCommand command = ProcessedLogTestSupport.command();
        final RunClaim claim = new RunClaim(
                command.source(), command.requestId(), "runner-1", UUID.randomUUID(), "msg-1");
        ProcessedLogTestSupport.repository(LEASE)
                .insertNew(command, RequestFingerprint.of(command), claim);
        return command;
    }

    @Nested
    @DisplayName("claiming an authority before the POST")
    class Claiming {

        @Test
        void claiming_a_fresh_authority_should_write_a_pending_row_carrying_the_digest() {
            final DistributionCommand command = seededRequest();
            final UUID outputId = UUID.randomUUID();

            final boolean claimed = repository().claimPending(
                    outputId, command.source(), command.requestId(), AUTHORITY, DIGEST);

            assertThat(claimed).isTrue();
            final Row row = requireRow(command, AUTHORITY);
            assertThat(row.outputId()).isEqualTo(outputId);
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.requestDigest()).isEqualTo(DIGEST);
        }

        @Test
        void claiming_should_be_refused_once_the_authority_is_posted() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, DIGEST);
            repository.recordPosted(command.source(), command.requestId(), AUTHORITY);

            final boolean claimed = repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, OTHER_DIGEST);

            assertThat(claimed).isFalse();
            final Row row = requireRow(command, AUTHORITY);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.requestDigest())
                    .as("a refused claim must not overwrite what was actually sent")
                    .isEqualTo(DIGEST);
        }

        @Test
        void claiming_should_be_admitted_again_after_a_failure_so_only_the_failed_work_repeats() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            final UUID firstId = UUID.randomUUID();
            repository.claimPending(
                    firstId, command.source(), command.requestId(), AUTHORITY, DIGEST);
            repository.recordFailed(command.source(), command.requestId(), AUTHORITY);

            final boolean claimed = repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, OTHER_DIGEST);

            assertThat(claimed).isTrue();
            final Row row = requireRow(command, AUTHORITY);
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.outputId())
                    .as("the row keeps the identity it was first written under")
                    .isEqualTo(firstId);
            assertThat(row.requestDigest())
                    .as("the digest describes the body about to be sent, so a re-claim replaces it")
                    .isEqualTo(OTHER_DIGEST);
        }

        @Test
        void each_authority_of_one_request_should_get_its_own_row() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();

            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, DIGEST);
            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), OTHER_AUTHORITY, DIGEST);
            repository.recordPosted(command.source(), command.requestId(), AUTHORITY);

            assertThat(requireRow(command, AUTHORITY).status()).isEqualTo("POSTED");
            assertThat(requireRow(command, OTHER_AUTHORITY).status())
                    .as("posting one authority must say nothing about another")
                    .isEqualTo("PENDING");
        }
    }

    @Nested
    @DisplayName("recording the outcome of the POST")
    class Recording {

        @Test
        void recording_a_post_should_move_the_row_to_posted_and_move_the_timestamp_on() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, DIGEST);
            ageUpdatedAt(command, AUTHORITY);
            final Instant aged = requireRow(command, AUTHORITY).updatedAt();

            final boolean recorded =
                    repository.recordPosted(command.source(), command.requestId(), AUTHORITY);

            assertThat(recorded).isTrue();
            final Row row = requireRow(command, AUTHORITY);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.updatedAt()).isAfter(aged);
        }

        @Test
        void recording_a_failure_should_move_the_row_to_failed_and_keep_the_digest() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, DIGEST);

            final boolean recorded =
                    repository.recordFailed(command.source(), command.requestId(), AUTHORITY);

            assertThat(recorded).isTrue();
            final Row row = requireRow(command, AUTHORITY);
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.requestDigest())
                    .as("what was attempted is still the reconciliation evidence")
                    .isEqualTo(DIGEST);
        }

        /**
         * The case that makes the predicate worth having.
         *
         * <p>Two deliveries of a request can overlap: a runner whose claim was reclaimed while it
         * worked is still running, and its POST can finish after the winner's. If its late failure
         * could move a POSTED authority back to FAILED, the next delivery would re-claim that
         * authority and POST a second, non-idempotent {@code add-informant-register} — a duplicate
         * register row created by the log that exists to prevent one.
         */
        @Test
        void a_late_failure_should_never_move_an_authority_out_of_posted() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, DIGEST);
            repository.recordPosted(command.source(), command.requestId(), AUTHORITY);

            final boolean recorded =
                    repository.recordFailed(command.source(), command.requestId(), AUTHORITY);

            assertThat(recorded)
                    .as("the loser of an overlap is told its write affected nothing")
                    .isFalse();
            assertThat(requireRow(command, AUTHORITY).status()).isEqualTo("POSTED");
        }

        @Test
        void recording_a_post_twice_should_leave_the_row_posted_and_affect_nothing_the_second_time() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, DIGEST);
            repository.recordPosted(command.source(), command.requestId(), AUTHORITY);

            final boolean again =
                    repository.recordPosted(command.source(), command.requestId(), AUTHORITY);

            assertThat(again).isFalse();
            assertThat(requireRow(command, AUTHORITY).status()).isEqualTo("POSTED");
        }

        @Test
        void recording_a_post_after_a_failure_should_be_admitted_so_the_success_is_the_last_word() {
            final DistributionCommand command = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(
                    UUID.randomUUID(), command.source(), command.requestId(), AUTHORITY, DIGEST);
            repository.recordFailed(command.source(), command.requestId(), AUTHORITY);

            final boolean recorded =
                    repository.recordPosted(command.source(), command.requestId(), AUTHORITY);

            assertThat(recorded)
                    .as("an authority that did go must end POSTED, or it would be sent again")
                    .isTrue();
            assertThat(requireRow(command, AUTHORITY).status()).isEqualTo("POSTED");
        }

        @Test
        void recording_an_outcome_for_an_unclaimed_authority_should_affect_nothing() {
            final DistributionCommand command = seededRequest();

            final boolean recorded =
                    repository().recordPosted(command.source(), command.requestId(), AUTHORITY);

            assertThat(recorded).isFalse();
            assertThat(row(command, AUTHORITY)).isEmpty();
        }
    }

    private record Row(
            UUID outputId, String status, String requestDigest, Instant createdAt, Instant updatedAt) {
    }

    private static Optional<Row> row(final DistributionCommand command, final String authority) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT output_id, status, request_digest, created_at, updated_at
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                           AND prosecution_authority_id = :authority
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param("authority", authority)
                .query((rs, rowNumber) -> new Row(
                        rs.getObject("output_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("request_digest"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional();
    }

    private static Row requireRow(final DistributionCommand command, final String authority) {
        return row(command, authority).orElseThrow(() -> new IllegalStateException(
                "no processed_output row for " + authority));
    }

    /**
     * Seeds {@code updated_at} into the past by the database's own clock, so "the write moved the
     * timestamp on" can be asserted strictly rather than against a value written moments earlier.
     */
    private static void ageUpdatedAt(final DistributionCommand command, final String authority) {
        final JdbcClient client = ProcessedLogTestSupport.jdbcClient();
        final int aged = client
                .sql("""
                        UPDATE processed_output
                           SET updated_at = now() - interval '1 hour'
                         WHERE source = :source AND request_id = :requestId
                           AND prosecution_authority_id = :authority
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param("authority", authority)
                .update();
        if (aged != 1) {
            throw new IllegalStateException("expected one output row to age, aged " + aged);
        }
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
