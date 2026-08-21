package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
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
 * The accepted crash window: a run finishes, the pod dies before its outcome is written, and the
 * redelivery runs the request again (spec US2, SC-002).
 *
 * <p>What the service promises is not a bounded number of runs but their shape — never two at once,
 * and never any run once COMPLETED has been durably recorded. Both halves are asserted here, and the
 * crash is simulated the only honest way: the outcome write simply never happens.
 *
 * <p>Sequencing is proven by what the guard refuses. While the crashed runner's claim is still live,
 * the redelivery is handed back rather than run; only once the claim has lapsed does a second run
 * become possible. That ordering is the whole of "never concurrent" at this layer.
 */
class CrashWindowIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private final DistributionCommand command = ProcessedLogTestSupport.command();
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE);

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    private static DeliveryIdentity delivery(final int number) {
        return new DeliveryIdentity("msg-" + number, "runner-" + number + "/delivery-" + number);
    }

    /**
     * A run that started and never recorded anything, leaving a claim nobody will release — aged an
     * hour into the past by the database's clock, which is what the next delivery finds.
     */
    private RunClaim crashedRun(final int number) {
        final RunClaim claim = runClaimOf(guard.admit(command, delivery(number)));
        ProcessedLogTestSupport.expireClaim(command.source(), command.requestId());
        return claim;
    }

    @Test
    @DisplayName("while the crashed runner's claim is still live, the redelivery waits")
    void a_redelivery_should_not_run_concurrently_with_a_claim_that_is_still_live() {
        runClaimOf(guard.admit(command, delivery(1)));

        final GuardDecision redelivery = guard.admit(command, delivery(2));

        assertThat(redelivery).isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
        assertThat(row().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("once the claim has lapsed, the redelivery runs — one further run, in sequence")
    void a_redelivery_should_rerun_the_request_after_the_claim_lapses() {
        crashedRun(1);

        final RunClaim rerun = runClaimOf(guard.admit(command, delivery(2)));
        guard.recordCompletion(rerun, CompletionReason.NO_AUTHORITIES);

        final Row row = row();
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.completionReason()).isEqualTo("no-authorities");
        assertThat(row.attempts()).isEqualTo(2);
        assertThat(row.claimOwner()).isNull();
    }

    @Test
    @DisplayName("a crash repeated in the same window repeats the run, one at a time")
    void repeated_crashes_should_produce_repeated_sequential_runs() {
        crashedRun(1);
        crashedRun(2);
        crashedRun(3);

        final Row row = row();
        assertThat(row.attempts()).isEqualTo(3);
        assertThat(row.status()).isEqualTo("RECEIVED");
        // Three runs started, one claim: the row can only ever hold the newest.
        assertThat(row.claimOwner()).isEqualTo("runner-3/delivery-3");
    }

    @Test
    @DisplayName("no run happens once COMPLETED is durable, however many redeliveries arrive")
    void a_completed_record_should_end_the_window() {
        final RunClaim first = runClaimOf(guard.admit(command, delivery(1)));
        guard.recordCompletion(first, CompletionReason.NO_AUTHORITIES);

        final GuardDecision redelivery = guard.admit(command, delivery(2));

        assertThat(redelivery).isEqualTo(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED));
        assertThat(row().attempts()).isEqualTo(1);
    }
}
