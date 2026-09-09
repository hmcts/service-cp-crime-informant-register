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
import uk.gov.hmcts.cp.informantregister.domain.DeadLetterReason;
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
 * discards its result, says so at WARN, counts itself, and settles the delivery without touching the
 * record. Overwriting the new owner's work instead is how a request gets submitted twice.
 *
 * <p><strong>How it settles depends on the delivery budget.</strong> Ordinarily the delivery goes
 * back: a redelivery meets the reclaiming runner's durable outcome and finishes in a moment. On the
 * last delivery the message is entitled to there is no redelivery to meet anything, so the message is
 * parked here, under this service's own reason. Both are asserted, and both against the real
 * conditional SQL — the mocked-repository suite
 * ({@code application/OutcomeWriteExhaustionTest}) proves the decision, but only Postgres can prove
 * that the predicate actually refused the write and left the holder's row alone.
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
    @ParameterizedTest(name = "{0}")
    @EnumSource(OutcomeWrite.class)
    @DisplayName("a claim retaken by the same runner is still a different claim")
    void a_stale_write_should_be_refused_even_when_the_owner_is_unchanged(final OutcomeWrite outcome) {
        final DistributionCommand redelivered = ProcessedLogTestSupport.command();
        final String sameOwner = "runner-1/delivery-1";
        final RunClaim lapsed = runClaimOf(
                guard.admit(redelivered, new DeliveryIdentity("msg-1", sameOwner)));
        ProcessedLogTestSupport.expireClaim(redelivered.source(), redelivered.requestId());
        final RunClaim retaken = runClaimOf(
                guard.admit(redelivered, new DeliveryIdentity("msg-1", sameOwner)));
        assertThat(retaken.owner()).isEqualTo(lapsed.owner());
        assertThat(retaken.token()).isNotEqualTo(lapsed.token());
        final Row before =
                ProcessedLogTestSupport.requireRow(redelivered.source(), redelivered.requestId());

        final GuardDecision decision = write(outcome, lapsed);

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.STALE_RUNNER));
        assertThat(rejections()).isEqualTo(1);
        // Each of the three writes has to carry the predicate; the record is left exactly as the
        // runner that holds the claim left it.
        assertThat(ProcessedLogTestSupport.requireRow(redelivered.source(), redelivered.requestId()))
                .isEqualTo(before);
        assertThat(before.claimToken()).isEqualTo(retaken.token());
    }

    /**
     * The same rejection, on a runner that has no delivery left to hand back to.
     *
     * <p><strong>How this happens.</strong> A runner holds the fifth and last delivery of a message
     * and wedges. Its claim lapses at the lease, and a support resubmission — a fresh broker
     * {@code messageId} carrying the same {@code requestId}, which is the supported way to recover a
     * request — reclaims the request and starts running. The wedged runner then comes back and tries
     * to write its outcome. The predicate refuses it, as it does for any superseded runner; what is
     * different is that handing the delivery back would return a message the broker will immediately
     * dead-letter under {@code MaxDeliveryCountExceeded}, with no reason of ours behind it and
     * nothing in the log index naming a stale runner. A rise in stale runners is the signal that
     * leases are too short for the pipeline, and this is the delivery where losing it costs most.
     *
     * <p>{@code EXHAUSTION} is the case this suite could not previously reach at all: every claim it
     * built used the two-argument {@link DeliveryIdentity}, which defaults the budget to
     * {@code false}, so {@code recordExhaustion} — a method only ever called <em>on</em> the final
     * delivery — was asserted here under a budget it can never actually run with.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(OutcomeWrite.class)
    @DisplayName("a superseded runner on its final delivery parks the message with our own reason")
    void a_stale_outcome_write_on_the_final_delivery_should_park_rather_than_abandon(
            final OutcomeWrite outcome) {
        final DistributionCommand request = ProcessedLogTestSupport.command();
        final RunClaim wedged = runClaimOf(guard.admit(
                request, new DeliveryIdentity("msg-1", "runner-1/delivery-5", true)));
        ProcessedLogTestSupport.expireClaim(request.source(), request.requestId());
        runClaimOf(guard.admit(request, new DeliveryIdentity("msg-2-resubmitted", CURRENT_OWNER)));
        final Row before =
                ProcessedLogTestSupport.requireRow(request.source(), request.requestId());

        final GuardDecision decision = write(outcome, wedged);

        assertThat(decision)
                .as("handing it back would spend the message on the broker's own reason")
                .isEqualTo(new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, ReasonCode.STALE_RUNNER));
        assertThat(ProcessedLogTestSupport.requireRow(request.source(), request.requestId()))
                .as("the parking is attribution, not state: the row belongs to the current holder")
                .isEqualTo(before);
    }

    /**
     * The row the parking must not touch, named field by field rather than compared wholesale.
     *
     * <p>A structural comparison passes if two fields change and cancel out in the reading; this
     * says which values the reclaiming runner is entitled to still find when it writes its own
     * outcome. It is the {@code EXHAUSTION} write specifically, because that is the one whose
     * statement also stamps {@code exhausted_message_id} and {@code failure_reason} — the two
     * columns a superseded runner could most plausibly corrupt.
     */
    @Test
    @DisplayName("parking on the final delivery leaves the reclaiming runner's row untouched")
    void a_parked_stale_write_should_leave_the_current_owners_row_exactly_as_it_was() {
        final DistributionCommand request = ProcessedLogTestSupport.command();
        final RunClaim wedged = runClaimOf(guard.admit(
                request, new DeliveryIdentity("msg-1", "runner-1/delivery-5", true)));
        ProcessedLogTestSupport.expireClaim(request.source(), request.requestId());
        final RunClaim reclaimed = runClaimOf(
                guard.admit(request, new DeliveryIdentity("msg-2-resubmitted", CURRENT_OWNER)));

        write(OutcomeWrite.EXHAUSTION, wedged);

        final Row row = ProcessedLogTestSupport.requireRow(request.source(), request.requestId());
        assertThat(row.status())
                .as("the reclaiming run is still in flight; nothing terminal was written")
                .isEqualTo("RECEIVED");
        assertThat(row.claimOwner()).isEqualTo(CURRENT_OWNER);
        assertThat(row.claimToken()).isEqualTo(reclaimed.token());
        assertThat(row.failureReason())
                .as("the parking is the message's fate, never the request's")
                .isNull();
        assertThat(row.exhaustedMessageId())
                .as("the superseded delivery must not stamp itself as the one that exhausted it")
                .isNull();
        assertThat(row.completionReason()).isNull();
        // The wedged run still counted as a run start; it is only its outcome that is discarded.
        assertThat(row.attempts()).isEqualTo(2);
    }

    /**
     * The attribution the parking exists to buy, against the real store.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(OutcomeWrite.class)
    @DisplayName("a parked stale write says which request it parked and why")
    void a_parked_stale_write_should_be_visible(final OutcomeWrite outcome) {
        final DistributionCommand request = ProcessedLogTestSupport.command();
        final RunClaim wedged = runClaimOf(guard.admit(
                request, new DeliveryIdentity("msg-1", "runner-1/delivery-5", true)));
        ProcessedLogTestSupport.expireClaim(request.source(), request.requestId());
        runClaimOf(guard.admit(request, new DeliveryIdentity("msg-2-resubmitted", CURRENT_OWNER)));

        write(outcome, wedged);

        assertThat(rejections())
                .as("a parked stale runner is still a stale runner and still counts as one")
                .isEqualTo(1);
        assertThat(captured.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains(request.requestId().toString())
                        .contains(ReasonCode.STALE_RUNNER.code()));
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
