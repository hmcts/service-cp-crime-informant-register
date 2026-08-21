package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several deliveries of one request, arriving at the same instant, produce exactly one run (spec
 * FR-008, SC-002).
 *
 * <p>The deliveries are genuinely concurrent — one thread each, released together off a barrier, each
 * with its own guard and its own claim owner, exactly as separate pods would be. A test that called
 * the guard eight times in a row would prove nothing about the race this claim exists to settle.
 *
 * <p>The losers' decision matters as much as the winner's: a competing delivery is handed back to the
 * broker, never acknowledged. Acknowledging it would drop a request that has not been processed, and
 * a delivery is acknowledged without a run in exactly one case — the record is already COMPLETED.
 */
class ClaimContentionIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int DELIVERIES = 8;

    private final ExecutorService executor = Executors.newFixedThreadPool(DELIVERIES);
    private final DistributionCommand command = ProcessedLogTestSupport.command();

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    /**
     * Releases {@link #DELIVERIES} deliveries of the same request at once and collects what the guard
     * decided for each.
     */
    private List<GuardDecision> deliverConcurrently() throws Exception {
        final CyclicBarrier startLine = new CyclicBarrier(DELIVERIES);
        final List<Callable<GuardDecision>> deliveries = new ArrayList<>();
        for (int delivery = 0; delivery < DELIVERIES; delivery++) {
            final String owner = "runner-" + delivery + "/delivery-" + delivery;
            final String messageId = "msg-" + delivery;
            deliveries.add(() -> {
                final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE);
                startLine.await();
                return guard.admit(command, new DeliveryIdentity(messageId, owner));
            });
        }

        final List<GuardDecision> decisions = new ArrayList<>();
        for (final Future<GuardDecision> outcome : executor.invokeAll(deliveries)) {
            decisions.add(outcome.get());
        }
        return decisions;
    }

    @Test
    @DisplayName("exactly one concurrent delivery may run; the rest are handed back")
    void concurrent_deliveries_of_one_request_should_produce_a_single_claim_winner() throws Exception {
        final List<GuardDecision> decisions = deliverConcurrently();

        assertThat(decisions).hasSize(DELIVERIES);
        assertThat(decisions).filteredOn(GuardDecision.Run.class::isInstance).hasSize(1);
        assertThat(decisions).filteredOn(decision -> !(decision instanceof GuardDecision.Run))
                .allSatisfy(decision -> assertThat(decision)
                        .isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED)));
    }

    @Test
    @DisplayName("a competing delivery is never acknowledged and never parked")
    void losing_deliveries_should_be_returned_for_retry_only() throws Exception {
        final List<GuardDecision> decisions = deliverConcurrently();

        assertThat(decisions).noneMatch(GuardDecision.Complete.class::isInstance);
        assertThat(decisions).noneMatch(GuardDecision.DeadLetter.class::isInstance);
    }

    @Test
    @DisplayName("only the winner's run is counted, and only the winner holds the claim")
    void the_row_should_record_one_run_start_owned_by_the_winner() throws Exception {
        final List<GuardDecision> decisions = deliverConcurrently();

        final GuardDecision.Run winner = (GuardDecision.Run) decisions.stream()
                .filter(GuardDecision.Run.class::isInstance)
                .findFirst()
                .orElseThrow();
        final Row row = row();
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.status()).isEqualTo("RECEIVED");
        assertThat(row.claimOwner()).isEqualTo(winner.claim().owner());
        assertThat(row.claimToken()).isEqualTo(winner.claim().token());
    }

    @Test
    @DisplayName("once the run has completed, every further concurrent delivery is acknowledged")
    void concurrent_redeliveries_of_a_completed_request_should_all_be_acknowledged() throws Exception {
        final GuardDecision.Run winner = (GuardDecision.Run) deliverConcurrently().stream()
                .filter(GuardDecision.Run.class::isInstance)
                .findFirst()
                .orElseThrow();
        ProcessedLogTestSupport.guard(LEASE)
                .recordCompletion(winner.claim(), CompletionReason.NO_AUTHORITIES);

        final List<GuardDecision> redeliveries = deliverConcurrently();

        assertThat(redeliveries).allSatisfy(decision -> assertThat(decision)
                .isEqualTo(new GuardDecision.Complete(ReasonCode.ALREADY_COMPLETED)));
        assertThat(row().attempts()).isEqualTo(1);
    }
}
