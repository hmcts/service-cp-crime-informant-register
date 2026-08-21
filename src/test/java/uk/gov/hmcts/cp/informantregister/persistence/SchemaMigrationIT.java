package uk.gov.hmcts.cp.informantregister.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the V1 processed-log schema against `data-model.md`.
 *
 * <p>Every fact the data model states about the two tables is asserted here — column types and
 * nullability, defaults and the deliberate absence of one, the keys, the check constraints, the
 * uniqueness rule, the foreign key's delete behaviour and the support index — so a migration edited
 * to suit a later story cannot quietly drop an invariant the guard depends on.
 *
 * <p>The four `processed_request` checks and the `processed_output` rules are asserted twice over:
 * once structurally, from the constraint definition Postgres reports, and once behaviourally, by
 * offering the database a row that breaks the rule and requiring it to refuse.
 */
class SchemaMigrationIT {

    private static final String REQUEST_TABLE = "processed_request";
    private static final String OUTPUT_TABLE = "processed_output";

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    // --- helpers ---------------------------------------------------------------------------

    private record Column(String dataType, boolean nullable, String columnDefault) {
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
    }

    private static Map<String, Column> columnsOf(final String table) throws SQLException {
        final Map<String, Column> columns = new LinkedHashMap<>();
        final String sql = """
                SELECT column_name, data_type, is_nullable, column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ?
                 ORDER BY ordinal_position
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString("column_name"), new Column(
                            rows.getString("data_type"),
                            "YES".equals(rows.getString("is_nullable")),
                            rows.getString("column_default")));
                }
            }
        }
        return columns;
    }

    private static Map<String, String> constraintsOf(final String table) throws SQLException {
        final Map<String, String> constraints = new LinkedHashMap<>();
        final String sql = """
                SELECT c.conname, pg_get_constraintdef(c.oid) AS definition
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'public' AND t.relname = ?
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.put(rows.getString("conname"), rows.getString("definition"));
                }
            }
        }
        return constraints;
    }

    private static List<String> indexDefinitionsOf(final String table) throws SQLException {
        final List<String> definitions = new ArrayList<>();
        final String sql = "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    definitions.add(rows.getString("indexdef"));
                }
            }
        }
        return definitions;
    }

    private static List<String> tableNames() throws SQLException {
        final List<String> tables = new ArrayList<>();
        final String sql = """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """;
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                tables.add(rows.getString("table_name"));
            }
        }
        return tables;
    }

    /**
     * Runs one statement inside a transaction that is always rolled back, so behavioural probes
     * leave no rows behind for the guard suites that share this container.
     */
    private static void inRolledBackTransaction(final String... statements) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (final String sql : statements) {
                    statement.executeUpdate(sql);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static String insertRequest(final String columns, final String values) {
        return "INSERT INTO " + REQUEST_TABLE + " (" + columns + ") VALUES (" + values + ")";
    }

    /**
     * The smallest valid `processed_request` row, for probes that need a parent to exist.
     */
    private static String insertValidRequest(final String source, final UUID requestId) {
        return insertRequest(
                "source, request_id, hearing_id, hearing_day, shared_time, event_type, "
                        + "request_fingerprint, status, attempts",
                "'" + source + "', '" + requestId + "', '" + UUID.randomUUID() + "', "
                        + "DATE '2026-08-20', TIMESTAMPTZ '2026-08-20T09:00:00Z', 'Hearing_Resulted', "
                        + "'fingerprint', 'RECEIVED', 1");
    }

    // --- tables ----------------------------------------------------------------------------

    @Test
    @DisplayName("V1 creates both processed-log tables")
    void migration_should_create_both_tables() throws SQLException {
        assertThat(tableNames()).contains(REQUEST_TABLE, OUTPUT_TABLE);
    }

    @Nested
    @DisplayName("processed_request")
    class ProcessedRequest {

        @Test
        void columns_should_have_the_documented_types_and_nullability() throws SQLException {
            final Map<String, Column> columns = columnsOf(REQUEST_TABLE);

            assertThat(columns).containsOnlyKeys(
                    "source", "request_id", "hearing_id", "hearing_day", "shared_time", "event_type",
                    "request_fingerprint", "status", "attempts", "completion_reason", "failure_reason",
                    "exhausted_message_id", "audit_note", "claim_owner", "claim_token",
                    "claim_expires_at", "created_at", "updated_at");

            assertThat(columns.get("source")).isEqualTo(new Column("text", false, null));
            assertThat(columns.get("request_id")).isEqualTo(new Column("uuid", false, null));
            assertThat(columns.get("hearing_id")).isEqualTo(new Column("uuid", false, null));
            assertThat(columns.get("hearing_day")).isEqualTo(new Column("date", false, null));
            assertThat(columns.get("shared_time"))
                    .isEqualTo(new Column("timestamp with time zone", false, null));
            assertThat(columns.get("event_type")).isEqualTo(new Column("text", false, null));
            assertThat(columns.get("request_fingerprint")).isEqualTo(new Column("text", false, null));
            assertThat(columns.get("status")).isEqualTo(new Column("text", false, null));

            assertThat(columns.get("completion_reason")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("failure_reason")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("exhausted_message_id")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("audit_note")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("claim_owner")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("claim_token")).isEqualTo(new Column("uuid", true, null));
            assertThat(columns.get("claim_expires_at"))
                    .isEqualTo(new Column("timestamp with time zone", true, null));
        }

        @Test
        void attempts_should_be_a_non_null_integer_defaulting_to_zero() throws SQLException {
            assertThat(columnsOf(REQUEST_TABLE).get("attempts"))
                    .isEqualTo(new Column("integer", false, "0"));
        }

        @Test
        void timestamps_should_default_to_the_database_clock() throws SQLException {
            final Map<String, Column> columns = columnsOf(REQUEST_TABLE);
            assertThat(columns.get("created_at"))
                    .isEqualTo(new Column("timestamp with time zone", false, "now()"));
            assertThat(columns.get("updated_at"))
                    .isEqualTo(new Column("timestamp with time zone", false, "now()"));
        }

        @Test
        void primary_key_should_be_the_composite_source_and_request_id() throws SQLException {
            assertThat(constraintsOf(REQUEST_TABLE).values())
                    .anySatisfy(definition ->
                            assertThat(definition).isEqualTo("PRIMARY KEY (source, request_id)"));
        }

        @Test
        void support_index_should_cover_hearing_id_and_hearing_day() throws SQLException {
            assertThat(indexDefinitionsOf(REQUEST_TABLE))
                    .anySatisfy(definition ->
                            assertThat(definition).contains("(hearing_id, hearing_day)"));
        }

        @Test
        void status_check_should_name_the_four_states() throws SQLException {
            final String definition = constraintsOf(REQUEST_TABLE).get("processed_request_status_chk");
            assertThat(definition).isNotNull()
                    .contains("RECEIVED", "RETRYING", "COMPLETED", "FAILED");
        }

        @Test
        void status_check_should_reject_a_state_outside_the_enumeration() {
            assertThatThrownBy(() -> inRolledBackTransaction(insertRequest(
                    "source, request_id, hearing_id, hearing_day, shared_time, event_type, "
                            + "request_fingerprint, status, attempts",
                    "'RESULTS', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', "
                            + "DATE '2026-08-20', TIMESTAMPTZ '2026-08-20T09:00:00Z', 'Hearing_Resulted', "
                            + "'fingerprint', 'PENDING', 1")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_request_status_chk");
        }

        @Test
        void attempts_check_should_require_a_non_negative_count() throws SQLException {
            assertThat(constraintsOf(REQUEST_TABLE).get("processed_request_attempts_chk"))
                    .isNotNull()
                    .contains("attempts >= 0");
        }

        @Test
        void attempts_check_should_reject_a_negative_count() {
            assertThatThrownBy(() -> inRolledBackTransaction(insertRequest(
                    "source, request_id, hearing_id, hearing_day, shared_time, event_type, "
                            + "request_fingerprint, status, attempts",
                    "'RESULTS', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', "
                            + "DATE '2026-08-20', TIMESTAMPTZ '2026-08-20T09:00:00Z', 'Hearing_Resulted', "
                            + "'fingerprint', 'RECEIVED', -1")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_request_attempts_chk");
        }

        @Test
        void claim_triple_check_should_bind_all_three_columns_together() throws SQLException {
            assertThat(constraintsOf(REQUEST_TABLE).get("processed_request_claim_triple_chk"))
                    .isNotNull()
                    .contains("claim_owner", "claim_token", "claim_expires_at");
        }

        /**
         * Which of the claim's three columns a candidate row populates.
         */
        record ClaimTriple(boolean owner, boolean token, boolean expiresAt) {
            @Override
            public String toString() {
                return "owner=" + state(owner) + ", token=" + state(token)
                        + ", expiresAt=" + state(expiresAt);
            }

            private static String state(final boolean populated) {
                return populated ? "set" : "null";
            }
        }

        /** The six ways a claim can be half-written. All must be refused. */
        static Stream<ClaimTriple> partiallyWrittenClaims() {
            return Stream.of(
                    new ClaimTriple(true, false, false),
                    new ClaimTriple(false, true, false),
                    new ClaimTriple(false, false, true),
                    new ClaimTriple(true, true, false),
                    new ClaimTriple(true, false, true),
                    new ClaimTriple(false, true, true));
        }

        /** The two ways a claim can be whole. Both must be allowed. */
        static Stream<ClaimTriple> wholeClaims() {
            return Stream.of(
                    new ClaimTriple(false, false, false),
                    new ClaimTriple(true, true, true));
        }

        private static String insertWithClaim(final ClaimTriple triple) {
            final List<String> columns = new ArrayList<>(List.of(
                    "source", "request_id", "hearing_id", "hearing_day", "shared_time", "event_type",
                    "request_fingerprint", "status", "attempts"));
            final List<String> values = new ArrayList<>(List.of(
                    "'RESULTS'", "'" + UUID.randomUUID() + "'", "'" + UUID.randomUUID() + "'",
                    "DATE '2026-08-20'", "TIMESTAMPTZ '2026-08-20T09:00:00Z'", "'Hearing_Resulted'",
                    "'fingerprint'", "'RECEIVED'", "1"));

            if (triple.owner()) {
                columns.add("claim_owner");
                values.add("'runner-1'");
            }
            if (triple.token()) {
                columns.add("claim_token");
                values.add("'" + UUID.randomUUID() + "'");
            }
            if (triple.expiresAt()) {
                columns.add("claim_expires_at");
                values.add("now() + interval '5 minutes'");
            }
            return insertRequest(String.join(", ", columns), String.join(", ", values));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("partiallyWrittenClaims")
        void claim_triple_check_should_reject_every_partially_written_claim(final ClaimTriple triple) {
            // Enumerated rather than sampled: the constraint is written as two equalities, and a
            // single example would pass against several wrong ways of writing them.
            assertThatThrownBy(() -> inRolledBackTransaction(insertWithClaim(triple)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_request_claim_triple_chk");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("wholeClaims")
        void claim_triple_check_should_accept_a_claim_that_is_all_set_or_all_null(final ClaimTriple triple) {
            assertThatCode(() -> inRolledBackTransaction(insertWithClaim(triple)))
                    .doesNotThrowAnyException();
        }

        @Test
        void exhausted_message_id_check_should_bind_the_failed_state() throws SQLException {
            assertThat(constraintsOf(REQUEST_TABLE).get("processed_request_exhausted_id_chk"))
                    .isNotNull()
                    .contains("exhausted_message_id IS NOT NULL");
        }

        @Test
        void exhausted_message_id_check_should_reject_a_failed_row_without_a_message_identity() {
            assertThatThrownBy(() -> inRolledBackTransaction(insertRequest(
                    "source, request_id, hearing_id, hearing_day, shared_time, event_type, "
                            + "request_fingerprint, status, attempts, failure_reason",
                    "'RESULTS', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', "
                            + "DATE '2026-08-20', TIMESTAMPTZ '2026-08-20T09:00:00Z', 'Hearing_Resulted', "
                            + "'fingerprint', 'FAILED', 5, 'PIPELINE_TRANSIENT_FAILURE'")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_request_exhausted_id_chk");
        }
    }

    @Nested
    @DisplayName("processed_output")
    class ProcessedOutput {

        @Test
        void columns_should_have_the_documented_types_and_nullability() throws SQLException {
            final Map<String, Column> columns = columnsOf(OUTPUT_TABLE);

            assertThat(columns).containsOnlyKeys(
                    "output_id", "source", "request_id", "prosecution_authority_id", "status",
                    "request_digest", "created_at", "updated_at");

            assertThat(columns.get("source")).isEqualTo(new Column("text", false, null));
            assertThat(columns.get("request_id")).isEqualTo(new Column("uuid", false, null));
            assertThat(columns.get("prosecution_authority_id"))
                    .isEqualTo(new Column("text", false, null));
            assertThat(columns.get("status")).isEqualTo(new Column("text", false, null));
            // The only nullable column: the digest is written once the outbound body exists.
            assertThat(columns.get("request_digest")).isEqualTo(new Column("text", true, null));
        }

        @Test
        void output_id_should_be_a_non_null_uuid_with_no_database_default() throws SQLException {
            // Application-generated (UUID v4), deliberately not a database default.
            assertThat(columnsOf(OUTPUT_TABLE).get("output_id"))
                    .isEqualTo(new Column("uuid", false, null));
        }

        @Test
        void timestamps_should_default_to_the_database_clock() throws SQLException {
            final Map<String, Column> columns = columnsOf(OUTPUT_TABLE);
            assertThat(columns.get("created_at"))
                    .isEqualTo(new Column("timestamp with time zone", false, "now()"));
            assertThat(columns.get("updated_at"))
                    .isEqualTo(new Column("timestamp with time zone", false, "now()"));
        }

        @Test
        void primary_key_should_be_the_single_output_id_column() throws SQLException {
            assertThat(constraintsOf(OUTPUT_TABLE).values())
                    .anySatisfy(definition ->
                            assertThat(definition).isEqualTo("PRIMARY KEY (output_id)"));
        }

        @Test
        void status_check_should_name_the_three_output_states() throws SQLException {
            final String definition = constraintsOf(OUTPUT_TABLE).get("processed_output_status_chk");
            assertThat(definition).isNotNull().contains("PENDING", "POSTED", "FAILED");
        }

        @Test
        void status_check_should_reject_a_state_outside_the_enumeration() {
            final UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest("RESULTS", requestId),
                    "INSERT INTO " + OUTPUT_TABLE
                            + " (output_id, source, request_id, prosecution_authority_id, status) VALUES ('"
                            + UUID.randomUUID() + "', 'RESULTS', '" + requestId + "', 'PA1', 'RECEIVED')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_status_chk");
        }

        @Test
        void one_output_per_authority_should_be_unique() throws SQLException {
            assertThat(constraintsOf(OUTPUT_TABLE).get("processed_output_unique_authority"))
                    .isNotNull()
                    .isEqualTo("UNIQUE (source, request_id, prosecution_authority_id)");
        }

        @Test
        void a_second_output_for_the_same_authority_should_be_rejected() {
            final UUID requestId = UUID.randomUUID();
            final String output = "INSERT INTO " + OUTPUT_TABLE
                    + " (output_id, source, request_id, prosecution_authority_id, status) VALUES ('%s', "
                    + "'RESULTS', '" + requestId + "', 'PA1', 'PENDING')";
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest("RESULTS", requestId),
                    output.formatted(UUID.randomUUID()),
                    output.formatted(UUID.randomUUID())))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_unique_authority");
        }

        @Test
        void foreign_key_should_restrict_deletion_of_the_parent_request() throws SQLException {
            assertThat(constraintsOf(OUTPUT_TABLE).get("processed_output_request_fk"))
                    .isNotNull()
                    .isEqualTo("FOREIGN KEY (source, request_id) REFERENCES processed_request(source, "
                            + "request_id) ON DELETE RESTRICT");
        }

        @Test
        void deleting_a_request_that_still_has_an_output_should_be_refused() {
            final UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest("RESULTS", requestId),
                    "INSERT INTO " + OUTPUT_TABLE
                            + " (output_id, source, request_id, prosecution_authority_id, status) VALUES ('"
                            + UUID.randomUUID() + "', 'RESULTS', '" + requestId + "', 'PA1', 'PENDING')",
                    "DELETE FROM " + REQUEST_TABLE + " WHERE source = 'RESULTS' AND request_id = '"
                            + requestId + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_request_fk");
        }
    }
}
