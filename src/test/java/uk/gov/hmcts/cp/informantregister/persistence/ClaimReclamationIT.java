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
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport;
import uk.gov.hmcts.cp.informantregister.support.ProcessedLogTestSupport.Row;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A claim its runner never released is reclaimable, and reclaiming it is itself a race exactly one
 * delivery wins (spec FR-008, and the "crash after RECEIVED" edge case).
 *
 * <p>Expiry is produced by ageing the claim an hour into the past with the database's own clock, not by
 * waiting: that is what a crashed runner leaves behind, with no sleep and no JVM clock anywhere near
 * the decision. The comparison stays where the data model puts it — inside the conditional update, on
 * the database's clock.
 */
class ClaimReclamationIT {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int RACERS = 6;

    private final ExecutorService executor = Executors.newFixedThreadPool(RACERS);
    private final DistributionCommand command = ProcessedLogTestSupport.command();
    private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LEASE);

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    private Row row() {
        return ProcessedLogTestSupport.requireRow(command.source(), command.requestId());
    }

    private static RunClaim runClaimOf(final GuardDecision decision) {
        assertThat(decision).isInstanceOf(GuardDecision.Run.class);
        return ((GuardDecision.Run) decision).claim();
    }

    /** A runner that took the claim and never came back; its claim is aged past its expiry. */
    private RunClaim crashedRunner() {
        final RunClaim claim = runClaimOf(
                guard.admit(command, new DeliveryIdentity("msg-1", "crashed-runner/delivery-1")));
        ProcessedLogTestSupport.expireClaim(command.source(), command.requestId());
        return claim;
    }

    private List<GuardDecision> raceToReclaim() throws Exception {
        final CyclicBarrier startLine = new CyclicBarrier(RACERS);
        final List<Callable<GuardDecision>> deliveries = new ArrayList<>();
        for (int racer = 0; racer < RACERS; racer++) {
            final String owner = "runner-" + racer + "/delivery-" + racer;
            final String messageId = "msg-reclaim-" + racer;
            deliveries.add(() -> {
                final IdempotencyGuard racingGuard = ProcessedLogTestSupport.guard(LEASE);
                startLine.await();
                return racingGuard.admit(command, new DeliveryIdentity(messageId, owner));
            });
        }

        final List<GuardDecision> decisions = new ArrayList<>();
        for (final Future<GuardDecision> outcome : executor.invokeAll(deliveries)) {
            decisions.add(outcome.get());
        }
        return decisions;
    }

    @Test
    @DisplayName("an expired claim is reclaimed by exactly one of several racing deliveries")
    void racing_deliveries_should_produce_one_reclaimer() throws Exception {
        crashedRunner();

        final List<GuardDecision> decisions = raceToReclaim();

        assertThat(decisions).filteredOn(GuardDecision.Run.class::isInstance).hasSize(1);
        assertThat(decisions).filteredOn(decision -> !(decision instanceof GuardDecision.Run))
                .allSatisfy(decision -> assertThat(decision)
                        .isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED)));

        final GuardDecision.Run winner = (GuardDecision.Run) decisions.stream()
                .filter(GuardDecision.Run.class::isInstance)
                .findFirst()
                .orElseThrow();
        final Row row = row();
        assertThat(row.claimOwner()).isEqualTo(winner.claim().owner());
        assertThat(row.claimToken()).isEqualTo(winner.claim().token());
        // One reclaim, one further run start: the crashed run and the reclaimed one.
        assertThat(row.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("reclaiming moves the claim and the attempt count, never the state")
    void a_reclaim_should_leave_the_record_in_the_state_it_found_it() {
        final RunClaim crashed = crashedRunner();
        final Row before = row();
        // Both timestamps come off the same row, so the fixture's claim is provably stale by the
        // database's reckoning rather than by this JVM's.
        assertThat(before.claimExpiresAt()).isBefore(before.createdAt());

        final RunClaim reclaimed = runClaimOf(guard.admit(
                command, new DeliveryIdentity("msg-2", "runner-2/delivery-1")));

        final Row row = row();
        assertThat(row.status()).isEqualTo(before.status()).isEqualTo("RECEIVED");
        assertThat(row.attempts()).isEqualTo(before.attempts() + 1);
        assertThat(reclaimed.token()).isNotEqualTo(crashed.token());
        assertThat(row.claimToken()).isEqualTo(reclaimed.token());
        assertThat(row.claimExpiresAt()).isAfter(before.claimExpiresAt());
    }

    @Test
    @DisplayName("a live claim is not reclaimable")
    void a_delivery_arriving_while_the_claim_is_live_should_be_handed_back() {
        guard.admit(command, new DeliveryIdentity("msg-1", "runner-1/delivery-1"));

        final GuardDecision decision =
                guard.admit(command, new DeliveryIdentity("msg-2", "runner-2/delivery-1"));

        assertThat(decision).isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
        assertThat(row().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an absent claim on a non-terminal record is reclaimed without waiting for anything")
    void a_record_left_with_no_claim_should_be_reclaimed_immediately() {
        final RunClaim first =
                runClaimOf(guard.admit(command, new DeliveryIdentity("msg-1", "runner-1/delivery-1")));
        guard.recordTransientFailure(first, ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        assertThat(row().claimOwner()).isNull();

        final RunClaim reclaimed = runClaimOf(guard.admit(
                command, new DeliveryIdentity("msg-2", "runner-2/delivery-1")));

        final Row row = row();
        assertThat(row.status()).isEqualTo("RETRYING");
        assertThat(row.claimToken()).isEqualTo(reclaimed.token());
        assertThat(row.attempts()).isEqualTo(2);
    }
}
