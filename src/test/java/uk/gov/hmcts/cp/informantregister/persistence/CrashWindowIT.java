package uk.gov.hmcts.cp.informantregister.persistence;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
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
import static org.awaitility.Awaitility.await;

/**
 * The accepted crash window: a run finishes, the pod dies before its outcome is written, and the
 * redelivery runs the request again (spec US2, SC-002).
 *
 * <p>What the service promises is not a bounded number of runs but their shape — never two at once,
 * and never any run once COMPLETED has been durably recorded. Both halves are asserted here, and the
 * crash is simulated the only honest way: the outcome write simply never happens.
 *
 * <p><strong>The lease is raced, not rewritten.</strong> This suite used to age the claim with
 * {@code ProcessedLogTestSupport.expireClaim(...)}, which sets {@code claim_expires_at} into the
 * past directly. That proves the reclaim predicate and nothing else: a repository that ignored its
 * configured lease, or wrote an expiry from the wrong clock, passed just the same, and the promise
 * being made here — that a crashed runner's claim lapses <em>by itself</em>, with no operator action
 * — was never under test. The cases below configure a real, short lease and wait for the database's
 * own clock to pass it. Nothing touches {@code claim_expires_at}.
 *
 * <p>Polling {@code admit} is safe to do while waiting: a delivery that cannot take the claim is
 * handed back by a conditional update that matched no row, so it writes nothing and leaves
 * {@code attempts} alone. The waits below therefore cost time and not state.
 */
class CrashWindowIT {

    /**
     * Long enough that "the claim is still live" is not a race on any machine. The cases holding
     * this lease assert what the guard refuses while a claim is held, and none of them waits.
     */
    private static final Duration HELD_LEASE = Duration.ofMinutes(5);

    /**
     * Short enough to wait out, and the real thing: the guard writes {@code now() + this} and the
     * cases below wait for it to pass.
     */
    private static final Duration LAPSING_LEASE = Duration.ofSeconds(2);

    /** Comfortably past the lease, so a slow container is a slow pass rather than a failure. */
    private static final Duration LAPSE_BUDGET = Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final DistributionCommand command = ProcessedLogTestSupport.command();

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

    /** The same delivery, arriving as the last one the queue's budget permits. */
    private static DeliveryIdentity finalPermittedDelivery(final int number) {
        return new DeliveryIdentity(
                "msg-" + number, "runner-" + number + "/delivery-" + number, true);
    }

    @Nested
    @DisplayName("while the crashed runner's claim is still live")
    class WhileTheClaimIsLive {

        private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(HELD_LEASE);

        @Test
        @DisplayName("the redelivery waits: it is handed back, and the request is not run twice")
        void a_redelivery_should_not_run_concurrently_with_a_claim_that_is_still_live() {
            runClaimOf(guard.admit(command, delivery(1)));

            final GuardDecision redelivery = guard.admit(command, delivery(2));

            assertThat(redelivery).isEqualTo(new GuardDecision.Abandon(ReasonCode.CLAIM_NOT_ACQUIRED));
            assertThat(row().attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("the final permitted delivery is parked with our own reason, not the broker's")
        void the_last_delivery_should_be_parked_rather_than_handed_back_to_nobody() {
            // Abandoning here would hand the delivery back to a broker that has no delivery left to
            // make, and the message would be parked under the broker's own reason with no code of
            // ours and no metric behind it.
            runClaimOf(guard.admit(command, delivery(1)));

            final GuardDecision lastChance = guard.admit(command, finalPermittedDelivery(2));

            assertThat(lastChance).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.CLAIM_NOT_ACQUIRED));
        }

        @Test
        @DisplayName("parking it leaves the holder's row untouched: the run in flight may still win")
        void the_last_delivery_should_not_write_the_holders_row() {
            final RunClaim holder = runClaimOf(guard.admit(command, delivery(1)));

            guard.admit(command, finalPermittedDelivery(2));

            final Row row = row();
            assertThat(row.status()).isEqualTo("RECEIVED");
            assertThat(row.attempts()).isEqualTo(1);
            assertThat(row.claimOwner()).isEqualTo(holder.owner());
            assertThat(row.claimToken()).isEqualTo(holder.token());
            assertThat(row.failureReason()).isNull();
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

    @Nested
    @DisplayName("once the lease lapses of its own accord")
    class OnceTheLeaseLapses {

        private final IdempotencyGuard guard = ProcessedLogTestSupport.guard(LAPSING_LEASE);

        /** The pod dies here: the claim is taken and no outcome is ever recorded against it. */
        private RunClaim aCrashedRun(final int number) {
            return runClaimOf(guard.admit(command, delivery(number)));
        }

        /**
         * A later delivery, which cannot run until the crashed claim has expired by itself. The
         * assertion inside the wait is what retries: while the claim is live the guard hands the
         * delivery back, and {@code runClaimOf} refuses it.
         */
        private RunClaim theNextDeliveryOnceTheClaimLapses(final int number) {
            final AtomicReference<RunClaim> reclaimed = new AtomicReference<>();
            await().atMost(LAPSE_BUDGET)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(() ->
                            reclaimed.set(runClaimOf(guard.admit(command, delivery(number)))));
            return reclaimed.get();
        }

        @Test
        @DisplayName("the expiry was written from the configured lease, by the database's clock")
        void the_claim_expiry_should_be_derived_from_the_lease() {
            // The assertion expireClaim(...) made impossible, and the reason the lease matters at
            // all: the invariant that a claim lapses before the broker redelivers is enforced
            // against this value, so it has to be this value that reaches the column. One statement
            // writes created_at and claim_expires_at from the same transaction clock, so the
            // relationship is exact rather than approximate.
            aCrashedRun(1);

            final Row row = row();

            assertThat(row.claimExpiresAt()).isEqualTo(row.createdAt().plus(LAPSING_LEASE));
        }

        @Test
        @DisplayName("the redelivery reruns the request — one further run, in sequence")
        void a_redelivery_should_rerun_the_request_after_the_claim_lapses() {
            aCrashedRun(1);

            final RunClaim rerun = theNextDeliveryOnceTheClaimLapses(2);
            guard.recordCompletion(rerun, CompletionReason.NO_AUTHORITIES);

            final Row row = row();
            assertThat(row.status()).isEqualTo("COMPLETED");
            assertThat(row.completionReason()).isEqualTo("no-authorities");
            assertThat(row.attempts()).isEqualTo(2);
            assertThat(row.claimOwner()).isNull();
        }

        @Test
        @DisplayName("the reclaiming run holds a claim of its own, not the dead runner's")
        void the_reclaimed_run_should_carry_a_freshly_minted_claim() {
            // Owner alone is never an acquisition condition: a token carried over from the dead
            // runner would let the reclaiming run write under an identity the row no longer holds.
            final RunClaim crashed = aCrashedRun(1);

            final RunClaim rerun = theNextDeliveryOnceTheClaimLapses(2);

            assertThat(rerun.owner()).isNotEqualTo(crashed.owner());
            assertThat(rerun.token()).isNotEqualTo(crashed.token());
            assertThat(row().claimToken()).isEqualTo(rerun.token());
        }

        @Test
        @DisplayName("a crash repeated in the same window repeats the run, one at a time")
        void repeated_crashes_should_produce_repeated_sequential_runs() {
            aCrashedRun(1);
            theNextDeliveryOnceTheClaimLapses(2);
            theNextDeliveryOnceTheClaimLapses(3);

            final Row row = row();
            assertThat(row.attempts()).isEqualTo(3);
            assertThat(row.status()).isEqualTo("RECEIVED");
            // Three runs started, one claim: the row can only ever hold the newest.
            assertThat(row.claimOwner()).isEqualTo("runner-3/delivery-3");
        }
    }
}
