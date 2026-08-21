package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spec SC-005: a reviewer with a request id and nothing else can follow the request from receipt to
 * settlement.
 *
 * <p>"And nothing else" is the requirement. No database, no broker tooling, no correlation with
 * timestamps across three components — one identifier, typed into a log search, and the whole life
 * of the request comes back in order. That is what the support conversation about a missing register
 * actually looks like, and it is the reason correlation is put in place before the first line about
 * a delivery is written and taken down after the last.
 *
 * <p>The happy path is asserted first, then the path that matters more: a request that fails, is
 * retried, and is finally parked. A trace that only holds while nothing goes wrong is no trace at
 * all — the request a reviewer looks up is, by definition, one that did not behave.
 *
 * <p>The suite reads its evidence through the MDC rather than by matching the identifier inside
 * message text. The MDC is what the JSON encoder puts on every line and therefore what a log index
 * makes searchable; a suite that matched substrings would pass on lines a reviewer could never find.
 */
class TraceabilityIT {

    private static final String SERVICE_LOGGERS = "uk.gov.hmcts.cp.informantregister";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    private static final String REQUEST_ID = "requestId";
    private static final String HEARING_ID = "hearingId";
    private static final String HEARING_DAY = "hearingDay";

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void migrateTheSharedStore() {
        ProcessedLogTestSupport.dataSource();
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Everything a reviewer would get back from searching the index for this request id.
     */
    private List<ILoggingEvent> traceOf(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> requestId.toString().equals(event.getMDCPropertyMap().get(REQUEST_ID)))
                .toList();
    }

    private static List<String> messagesOf(final List<ILoggingEvent> trace) {
        return trace.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private Optional<Row> row() {
        return ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId);
    }

    private void awaitStatus(final RequestStatus status) {
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                row().filter(found -> status.name().equals(found.status())).isPresent());
    }

    /**
     * The property the whole criterion rests on: a reviewer who found one line found them all.
     */
    private void assertEveryTracedLineIsFullyCorrelated(final List<ILoggingEvent> trace) {
        assertThat(trace)
                .as("a trace with nothing in it would satisfy everything below vacuously")
                .isNotEmpty();
        for (final ILoggingEvent line : trace) {
            assertThat(line.getMDCPropertyMap())
                    .as("line without the full correlation set: %s", line.getFormattedMessage())
                    .containsKeys(REQUEST_ID, HEARING_ID, HEARING_DAY);
            assertThat(line.getMDCPropertyMap().get(HEARING_ID))
                    .isEqualTo(hearingId.toString());
        }
    }

    // --- the trace ------------------------------------------------------------------------

    @Test
    @DisplayName("one request id yields the whole life of a successful request, in order")
    void should_trace_a_completed_request_from_receipt_to_settlement() {
        try (ConfigurableApplicationContext ignored = ServiceTestSupport.start(Map.of());
             CapturedLog log = CapturedLog.of(SERVICE_LOGGERS)) {

            ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
            awaitStatus(RequestStatus.COMPLETED);

            final List<ILoggingEvent> trace = traceOf(log);
            assertEveryTracedLineIsFullyCorrelated(trace);

            final List<String> lines = messagesOf(trace);
            assertThat(lines)
                    .as("received")
                    .anyMatch(line -> line.startsWith("Delivery received."))
                    .as("admitted to the state machine")
                    .anyMatch(line -> line.startsWith("Request recorded and claimed."))
                    .as("the ports were reached")
                    .anyMatch(line -> line.startsWith("Hearing payload obtained."))
                    .as("the outcome was recorded")
                    .anyMatch(line -> line.startsWith("Request completed."))
                    .as("and the delivery was settled — the half a reader cannot get from the row")
                    .anyMatch(line -> line.startsWith("Delivery acknowledged."));

            assertThat(indexOfFirst(lines, "Delivery received."))
                    .as("receipt comes before settlement, so the trace reads as a story")
                    .isLessThan(indexOfFirst(lines, "Delivery acknowledged."));
        }
    }

    @Test
    @DisplayName("one request id yields the whole life of a request that failed and was parked")
    void should_trace_a_failing_request_through_retries_to_the_dead_letter_queue() {
        try (ConfigurableApplicationContext ignored = ServiceTestSupport.start(
                Map.of("informantregister.stub.payload-failure-mode", "TRANSIENT"));
             CapturedLog log = CapturedLog.of(SERVICE_LOGGERS)) {

            ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));
            awaitStatus(RequestStatus.FAILED);

            final List<ILoggingEvent> trace = traceOf(log);
            assertEveryTracedLineIsFullyCorrelated(trace);

            final List<String> lines = messagesOf(trace);
            assertThat(lines)
                    .as("the failure itself, loudly and with a bounded reason")
                    .anyMatch(line -> line.startsWith("Pipeline run failed."))
                    .as("each failure recorded before the delivery went back")
                    .anyMatch(line -> line.startsWith("Run failed transiently;"))
                    .as("the parking, which is where a reviewer's search usually starts")
                    .anyMatch(line -> line.startsWith("Request parked after its final permitted delivery."))
                    .as("and the settlement that put it on the dead-letter queue")
                    .anyMatch(line -> line.startsWith("Delivery parked on the dead-letter queue."));

            assertThat(trace.stream()
                    .filter(event -> Level.ERROR.equals(event.getLevel()))
                    .count())
                    .as("one ERROR for each failed run, and none for anything else")
                    .isEqualTo(5);
        }
    }

    /**
     * Where the first line with the given opening appears in the trace.
     */
    private static int indexOfFirst(final List<String> lines, final String prefix) {
        for (int position = 0; position < lines.size(); position++) {
            if (lines.get(position).startsWith(prefix)) {
                return position;
            }
        }
        throw new AssertionError("no line starting with: " + prefix);
    }
}
