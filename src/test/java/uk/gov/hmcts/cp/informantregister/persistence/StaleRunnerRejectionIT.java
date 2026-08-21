package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A runner whose claim was reclaimed while it was working writes nothing (spec FR-008, FR-016).
 *
 * <p>Every outcome write is predicated on the owner <em>and</em> the token that acquired the claim it
 * settles, so a superseded runner's write matches zero rows. It then does the only safe thing: it
 * discards its result, says so at WARN, counts itself, and hands the delivery back. Overwriting the
 * new owner's work instead is how a request gets submitted twice.
 *
 * <p>All three outcome writes are exercised, not one of them. The predicate has to be on each, and a
 * suite that checked completion alone would pass with the retry and park statements unguarded.
 */
class StaleRunnerRejectionIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String COUNTER = "informantregister_stale_runner_rejections_total";
    private static final String CURRENT_OWNER = "runner-2/delivery-2";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE, metrics);
    private final DistributionCommand command = ProcessedLogTestSupport.command();
    private final Logger guardLogger = (Logger) LoggerFactory.getLogger(IdempotencyGuard.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    /** The claim of the runner that was superseded. */
    private RunClaim supersededClaim;

    /** The claim of the runner that reclaimed the request and is working now. */
    private RunClaim currentClaim;

    /** The three ways a run can end, all of them predicated on the claim. */
    private enum OutcomeWrite {
        COMPLETION, TRANSIENT_FAILURE, EXHAUSTION
    }

    @BeforeEach
    void supersedeARunner() {
        captured.start();
        guardLogger.addAppender(captured);

        supersededClaim = runClaimOf(
                guard.admit(command, new DeliveryIdentity("msg-1", "runner-1/delivery-1")));
        ProcessedLogTestSupport.expireClaim(command.source(), command.requestId());
        currentClaim = runClaimOf(
                guard.admit(command, new DeliveryIdentity("msg-2", CURRENT_OWNER)));
    }

    @AfterEach
    void detachAppender() {
        guardLogger.detachAppender(captured);
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    private double rejections() {
        final Counter counter = registry.find(COUNTER).counter();
        return counter == null ? 0 : counter.count();
    }

    private GuardDecision write(final OutcomeWrite outcome, final RunClaim claim) {
        return switch (outcome) {
            case COMPLETION -> guard.recordCompletion(claim, CompletionReason.NO_AUTHORITIES);
            case TRANSIENT_FAILURE ->
                    guard.recordTransientFailure(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
            case EXHAUSTION ->
                    guard.recordExhaustion(claim, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutcomeWrite.class)
    @DisplayName("a superseded runner's outcome write is refused and the delivery handed back")
    void a_stale_outcome_write_should_be_rejected(final OutcomeWrite outcome) {
        final Row before = row();

        final GuardDecision decision = write(outcome, supersededClaim);

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutcomeWrite.class)
    @DisplayName("a superseded runner is counted and says so at WARN")
    void a_stale_outcome_write_should_be_visible(final OutcomeWrite outcome) {
        write(outcome, supersededClaim);

        assertThat(rejections()).isEqualTo(1);
        assertThat(captured.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains(command.requestId().toString()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutcomeWrite.class)
    @DisplayName("the runner that holds the claim is not rejected")
    void the_current_runner_should_still_be_able_to_write_its_outcome(final OutcomeWrite outcome) {
        final GuardDecision decision = write(outcome, currentClaim);

        assertThat(decision).isNotEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
        assertThat(rejections()).isZero();
        assertThat(row().claimOwner()).isNull();
    }

    /**
     * Owner alone would not catch this. A delivery redelivered to the same instance carries the same
     * owner identity, so if its claim lapsed and it reclaimed the request, the only thing separating
     * the run that is now in flight from the run that was abandoned is the token minted at each
     * acquisition — which is exactly what data-model invariant 7 says it is for.
     */
    @Test
    @DisplayName("a claim retaken by the same runner is still a different claim")
    void a_stale_write_should_be_refused_even_when_the_owner_is_unchanged() {
        final DistributionCommand redelivered = ProcessedLogTestSupport.command();
        final String sameOwner = "runner-1/delivery-1";
        final RunClaim lapsed = runClaimOf(
                guard.admit(redelivered, new DeliveryIdentity("msg-1", sameOwner)));
        ProcessedLogTestSupport.expireClaim(redelivered.source(), redelivered.requestId());
        final RunClaim retaken = runClaimOf(
                guard.admit(redelivered, new DeliveryIdentity("msg-1", sameOwner)));
        assertThat(retaken.owner()).isEqualTo(lapsed.owner());
        assertThat(retaken.token()).isNotEqualTo(lapsed.token());

        final GuardDecision decision = guard.recordCompletion(lapsed, CompletionReason.NO_AUTHORITIES);

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
        assertThat(rejections()).isEqualTo(1);
        final Row row =
                ProcessedLogTestSupport.requireRow(redelivered.source(), redelivered.requestId());
        assertThat(row.status()).isEqualTo("RECEIVED");
        assertThat(row.claimToken()).isEqualTo(retaken.token());
        assertThat(row.completionReason()).isNull();
    }

    @Test
    @DisplayName("the superseded runner leaves the current claim exactly as it was")
    void a_rejected_write_should_not_disturb_the_current_claim() {
        write(OutcomeWrite.COMPLETION, supersededClaim);

        final Row row = row();
        assertThat(row.status()).isEqualTo("RECEIVED");
        assertThat(row.claimOwner()).isEqualTo(CURRENT_OWNER);
        assertThat(row.claimToken()).isEqualTo(currentClaim.token());
        assertThat(row.completionReason()).isNull();
        // The superseded run still counted as a run start; it is the outcome that is discarded.
        assertThat(row.attempts()).isEqualTo(2);
    }
}
