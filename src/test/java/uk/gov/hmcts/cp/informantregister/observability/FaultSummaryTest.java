package uk.gov.hmcts.cp.informantregister.observability;

import java.io.IOException;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a failure is allowed to say about itself.
 *
 * <p>This service reports failures by bounded, code-owned fact and never by the words a library
 * chose (constitution Principle VII, spec FR-012). A stack trace reaches a log index exactly as a
 * message does, and an exception somebody else wrote is the commonest way a payload fragment or a
 * credential escapes — so the summariser exists to give support the shape of a fault without its
 * text, and the assertions below are what stop it quietly becoming a {@code getMessage()} wrapper.
 *
 * <p>The markers are deliberately implausible strings: a test looking for a plausible word would
 * pass or fail for reasons unrelated to what was written.
 */
class FaultSummaryTest {

    private static final String MESSAGE_MARKER = "FAULTMESSAGEMARKERZQX7";

    @Nested
    @DisplayName("typeChain")
    class TypeChain {

        @Test
        @DisplayName("names the failure and every cause beneath it")
        void should_render_the_whole_chain() {
            final Throwable root = new IOException("root");
            final Throwable middle = new IllegalStateException("middle", root);
            final Throwable top = new RuntimeException("top", middle);

            assertThat(FaultSummary.typeChain(top))
                    .isEqualTo("java.lang.RuntimeException<-IllegalStateException<-IOException");
        }

        @Test
        @DisplayName("names a failure with no cause by itself")
        void should_render_a_lone_failure() {
            assertThat(FaultSummary.typeChain(new IllegalArgumentException("alone")))
                    .isEqualTo("java.lang.IllegalArgumentException");
        }

        @Test
        @DisplayName("writes no part of any exception's message, at any depth")
        void should_never_render_exception_text() {
            final Throwable root = new IOException(MESSAGE_MARKER);
            final Throwable top = new IllegalStateException(MESSAGE_MARKER, root);

            assertThat(FaultSummary.typeChain(top))
                    .as("the summariser's whole purpose is to omit this")
                    .doesNotContain(MESSAGE_MARKER);
        }

        @Test
        @DisplayName("stops at the depth bound rather than walking a library's chain forever")
        void should_bound_the_walk() {
            // A chain longer than the bound: the rendering has to stop, and say that it did.
            Throwable chain = new IOException("bottom");
            for (int link = 0; link < 20; link++) {
                chain = new IllegalStateException("link " + link, chain);
            }

            final String rendered = FaultSummary.typeChain(chain);

            assertThat(rendered.split("<-", -1))
                    .as("one entry per link, up to the bound, plus the truncation mark")
                    .hasSize(FaultSummary.MAX_CAUSE_DEPTH + 1);
            assertThat(rendered).endsWith("<-...");
        }

        @Test
        @DisplayName("a self-referential chain terminates instead of hanging")
        void should_survive_a_cycle() {
            final SelfCausing looping = new SelfCausing();

            assertThat(FaultSummary.typeChain(looping))
                    .as("a cycle is bounded by the same depth limit as any other chain")
                    .startsWith(SelfCausing.class.getName())
                    .endsWith("<-...");
        }

        @Test
        @DisplayName("a failure that is not there is said to be absent, never rendered as null")
        void should_describe_an_absent_failure() {
            assertThat(FaultSummary.typeChain(null)).isEqualTo("none");
        }
    }

    @Nested
    @DisplayName("sqlState")
    class SqlState {

        @Test
        @DisplayName("reports the state and vendor code of a nested database failure")
        void should_render_state_and_code() {
            final SQLException database = new SQLException(MESSAGE_MARKER, "23505", 7);
            final Throwable wrapped = new IllegalStateException("wrapped", database);

            assertThat(FaultSummary.sqlState(wrapped)).isEqualTo("23505/7");
        }

        @Test
        @DisplayName("writes no part of the database failure's message")
        void should_never_render_database_text() {
            final SQLException database = new SQLException(MESSAGE_MARKER, "23505", 7);

            assertThat(FaultSummary.sqlState(database)).doesNotContain(MESSAGE_MARKER);
        }

        @Test
        @DisplayName("reports an absent state as absent, so a log line reads honestly")
        void should_render_a_missing_state() {
            final SQLException stateless = new SQLException(MESSAGE_MARKER);

            assertThat(FaultSummary.sqlState(stateless)).isEqualTo("none/0");
        }

        @Test
        @DisplayName("says nothing when no database failure is in the chain")
        void should_be_absent_when_nothing_is_a_database_failure() {
            assertThat(FaultSummary.sqlState(new IllegalStateException("not a database problem")))
                    .isEqualTo("none");
            assertThat(FaultSummary.sqlState(null)).isEqualTo("none");
        }
    }

    @Nested
    @DisplayName("anyCause")
    class AnyCause {

        @Test
        @DisplayName("finds a cause anywhere beneath the failure")
        void should_match_a_nested_cause() {
            final Throwable top = new RuntimeException("top", new IOException("wanted"));

            assertThat(FaultSummary.anyCause(top, cause -> cause instanceof IOException)).isTrue();
        }

        @Test
        @DisplayName("does not match when nothing in the chain qualifies")
        void should_not_match_an_absent_cause() {
            assertThat(FaultSummary.anyCause(new RuntimeException("top"),
                    cause -> cause instanceof IOException)).isFalse();
        }

        @Test
        @DisplayName("terminates on a self-referential chain")
        void should_survive_a_cycle() {
            assertThat(FaultSummary.anyCause(new SelfCausing(), cause -> false)).isFalse();
        }

        @Test
        @DisplayName("a failure that is not there matches nothing")
        void should_not_match_an_absent_failure() {
            assertThat(FaultSummary.anyCause(null, cause -> true)).isFalse();
        }
    }

    /** A chain that never ends, of the kind a library can hand us. */
    private static final class SelfCausing extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SelfCausing() {
            super("self");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
