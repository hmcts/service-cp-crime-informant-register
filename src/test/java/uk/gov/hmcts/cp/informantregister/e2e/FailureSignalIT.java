package uk.gov.hmcts.cp.informantregister.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RequestStatus;
import uk.gov.hmcts.cp.informantregister.support.CapturedLog;
import uk.gov.hmcts.cp.informantregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spec FR-017: no failure is quiet, and none is noisy in the wrong way.
 *
 * <p>Two halves, and both are load-bearing. A failure must produce <strong>an ERROR log</strong>,
 * because that is what a human reads, and <strong>a metric</strong>, because that is what an alert
 * fires on; a failure with only one of them is either invisible on a dashboard or invisible to the
 * person paged by it.
 *
 * <p>"Exactly one" is asserted rather than "at least one" on purpose. A failure reported three times
 * on its way up a call stack is how an incident's real cause gets buried, and how an alert on ERROR
 * rate stops meaning anything. One failure, one line, at the layer that decided what to do about it.
 *
 * <p>Sanitised means the line carries the bounded reason code and the correlation identifiers, and
 * carries neither the producer's message nor an exception's own words: both are attacker-influenced
 * or PII-bearing text that would land in a log index (research §11).
 *
 * <p>The settlement-failure and lock-loss paths are proven by {@code SettlementFailureEdgeTest},
 * which can produce a broker that refuses a settlement; a real broker cannot be asked to.
 */
class FailureSignalIT {

    private static final String SERVICE_LOGGERS = "uk.gov.hmcts.cp.informantregister";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /** The queue's delivery budget, and so the number of failed runs a doomed request produces. */
    private static final int PERMITTED_DELIVERIES = 5;

    private final UUID requestId = UUID.randomUUID();
    private final UUID hearingId = UUID.randomUUID();

    @BeforeAll
    static void migrateTheSharedStore() {
        ProcessedLogTestSupport.dataSource();
    }

    @AfterEach
    void thawTheStore() {
        PostgresTestSupport.unpause();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static double counter(
            final MeterRegistry registry, final String name, final String tag, final String value) {
        final Counter found = registry.find(name).tag(tag, value).counter();
        return found == null ? 0 : found.count();
    }

    private static double counter(final MeterRegistry registry, final String name) {
        final Counter found = registry.find(name).counter();
        return found == null ? 0 : found.count();
    }

    private List<ILoggingEvent> errorsAbout(final CapturedLog log, final String needle) {
        return log.events().stream()
                .filter(event -> Level.ERROR.equals(event.getLevel()))
                .filter(event -> event.getFormattedMessage().contains(needle)
                        || requestId.toString().equals(event.getMDCPropertyMap().get("requestId")))
                .toList();
    }

    private static void assertSanitised(final ILoggingEvent line) {
        assertThat(line.getThrowableProxy())
                .as("a bounded reason, not somebody else's words: %s", line.getFormattedMessage())
                .isNull();
    }

    // --- contract validation ------------------------------------------------------------------

    @Test
    @DisplayName("a contract-invalid message produces one ERROR and one validation dead-letter")
    void should_signal_a_contract_validation_failure_exactly_once() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.start(Map.of());
             CapturedLog log = CapturedLog.of(SERVICE_LOGGERS)) {
            final MeterRegistry registry = context.getBean(MeterRegistry.class);
            final double before = counter(registry, ProcessingMetrics.DEAD_LETTERED,
                    ProcessingMetrics.REASON_TAG, DeadLetterReason.VALIDATION.label());

            final String messageId = ServiceTestSupport.publish(
                    ServiceTestSupport.contractInvalidBody(requestId, hearingId));

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    ServiceBusEmulatorTestSupport
                            .peekFor(messageId, SubQueue.DEAD_LETTER_QUEUE).isPresent());

            final List<ILoggingEvent> errors = errorsAbout(log, messageId);
            assertThat(errors)
                    .as("one failure, one line, at the layer that decided what to do about it")
                    .hasSize(1);
            assertThat(errors.getFirst().getFormattedMessage())
                    .startsWith("Message body failed contract validation");
            assertSanitised(errors.getFirst());

            assertThat(counter(registry, ProcessingMetrics.DEAD_LETTERED,
                    ProcessingMetrics.REASON_TAG, DeadLetterReason.VALIDATION.label()))
                    .as("countable, and labelled by why")
                    .isEqualTo(before + 1);
        }
    }

    // --- processing failure --------------------------------------------------------------------

    @Test
    @DisplayName("every failed run produces one ERROR and one failure count, retries included")
    void should_signal_every_failed_run_and_not_only_the_last() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.start(
                Map.of("informantregister.stub.payload-failure-mode", "TRANSIENT"));
             CapturedLog log = CapturedLog.of(SERVICE_LOGGERS)) {
            final MeterRegistry registry = context.getBean(MeterRegistry.class);
            final double failuresBefore = counter(registry, ProcessingMetrics.PROCESSING_FAILURES,
                    ProcessingMetrics.CLASSIFICATION_TAG, FailureClassification.TRANSIENT.label());
            final double parkedBefore = counter(registry, ProcessingMetrics.DEAD_LETTERED,
                    ProcessingMetrics.REASON_TAG, DeadLetterReason.EXHAUSTED.label());

            ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    ProcessedLogTestSupport.row(ProcessedLogTestSupport.SOURCE, requestId)
                            .filter(row -> RequestStatus.FAILED.name().equals(row.status()))
                            .isPresent());

            final List<ILoggingEvent> errors = errorsAbout(log, requestId.toString());
            assertThat(errors)
                    .as("a request retrying quietly is what this service exists to make visible")
                    .hasSize(PERMITTED_DELIVERIES);
            errors.forEach(line -> {
                assertThat(line.getFormattedMessage()).startsWith("Pipeline run failed.");
                assertThat(line.getFormattedMessage())
                        .contains(ReasonCode.PIPELINE_TRANSIENT_FAILURE.code());
                assertSanitised(line);
            });

            assertThat(counter(registry, ProcessingMetrics.PROCESSING_FAILURES,
                    ProcessingMetrics.CLASSIFICATION_TAG, FailureClassification.TRANSIENT.label()))
                    .as("every failed run counted, not only the terminal one")
                    .isEqualTo(failuresBefore + PERMITTED_DELIVERIES);
            assertThat(counter(registry, ProcessingMetrics.DEAD_LETTERED,
                    ProcessingMetrics.REASON_TAG, DeadLetterReason.EXHAUSTED.label()))
                    .isEqualTo(parkedBefore + 1);
        }
    }

    // --- store-outage suspension ------------------------------------------------------------------

    @Test
    @DisplayName("a store outage produces one ERROR naming the bounded reason, and one suspension count")
    void should_signal_a_store_outage_suspension_exactly_once() {
        try (ConfigurableApplicationContext context = ServiceTestSupport.start(Map.of());
             CapturedLog log = CapturedLog.of(SERVICE_LOGGERS)) {
            final MeterRegistry registry = context.getBean(MeterRegistry.class);
            final double before = counter(registry, ProcessingMetrics.INTAKE_SUSPENSIONS);

            PostgresTestSupport.pause();
            ServiceTestSupport.publish(ServiceTestSupport.validBody(requestId, hearingId));

            await().atMost(OBSERVED_WITHIN).pollInterval(POLL).until(() ->
                    counter(registry, ProcessingMetrics.INTAKE_SUSPENSIONS) > before);

            final List<ILoggingEvent> errors = log.events().stream()
                    .filter(event -> Level.ERROR.equals(event.getLevel()))
                    .filter(event -> event.getFormattedMessage()
                            .contains(ReasonCode.STORE_UNAVAILABLE.code()))
                    .toList();
            assertThat(errors)
                    .as("the outage is reported once, with the bounded reason a support tool reads")
                    .hasSize(1);
            assertSanitised(errors.getFirst());
        }
    }
}
