package uk.gov.hmcts.cp.informantregister.inbound;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedLogProbe;

/**
 * The only thing permitted to start or stop the consumer (research §7).
 *
 * <p>Intake is a decision, not a side effect. Left to itself the processor would start with the
 * context and consume whatever the queue had, whether or not this pod could record what it did —
 * and a store outage would then be spent one delivery at a time, five deliveries per message, until
 * the broker parked work that was never faulty. Putting every start and stop behind one component
 * is what makes "do not consume without a store" expressible at all.
 *
 * <p><strong>Three states, and the third is the one people forget.</strong>
 *
 * <ul>
 *   <li>{@code AWAITING_STORE} — the state every pod begins in. The context has refreshed and
 *       actuator is up, so readiness can report DOWN honestly, but nothing is being consumed and
 *       the schema has not been migrated yet.</li>
 *   <li>{@code RUNNING} — the store answered, the migration ran, the processor is consuming.</li>
 *   <li>{@code SUSPENDED} — the store went away while we were running. Intake stopped; the probe
 *       keeps asking; the moment the store answers again we are back in {@code RUNNING}.</li>
 *   <li>{@code STOPPING} and {@code STOPPED} — the context is closing. Terminal: nothing starts
 *       intake from here, however far through a start it already was.</li>
 * </ul>
 *
 * <p>{@code AWAITING_STORE} is distinct from {@code SUSPENDED} on purpose. Both consume nothing, but
 * only one of them is an incident: a pod that has not started consuming yet is a pod starting up,
 * and counting that as a suspension would put an incident on the dashboard at every deployment.
 * The gauge and the counter therefore describe the outage cycle — {@code RUNNING → SUSPENDED →
 * RUNNING} — and say nothing about the gated start, which readiness already describes.
 *
 * <p><strong>Migration happens here, before the first message.</strong> A no-op
 * {@code FlywayMigrationStrategy} keeps it off the context-refresh path so a pod can start without
 * its database; this controller runs it on the first successful probe, <em>before</em> the
 * processor is started. There is no ordering in which a delivery meets an unmigrated schema.
 *
 * <p><strong>Nothing transitions on a callback thread.</strong> Every start, stop and migration runs
 * on one single-threaded scheduled executor, which is also what runs the probe. That gives three
 * properties at once: transitions are serialised, so a resume cannot race a suspension; they are
 * idempotent, because each one checks the state it is transitioning from; and {@code stop()} is
 * never called from inside a message callback, which would deadlock — the shutdown waits for the
 * callbacks to drain, and one of them would be the caller.
 *
 * <p><strong>Single replica, for CRA-220.</strong> Suspension is a per-pod decision, so with several
 * replicas a store outage could still burn deliveries on pods that had not yet noticed. Cluster-safe
 * suspension belongs to the KEDA/scale-out story; the constraint is recorded in the plan.
 */
public class ConsumerLifecycleController implements SmartLifecycle, StoreGate {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerLifecycleController.class);

    /** How long shutdown waits for the transition thread to finish what it was doing. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    private final ServiceBusProcessorClient processor;
    private final ProcessedLogProbe storeProbe;
    private final Supplier<Flyway> flyway;
    private final ProcessingMetrics metrics;
    private final ServiceBusHealthIndicator health;
    private final Duration probeInterval;

    /**
     * The one thread every transition runs on, and the one the probe is scheduled on.
     */
    private final ScheduledExecutorService transitions = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "informantregister-intake");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * The state, held so that it can be moved <em>conditionally</em>.
     *
     * <p>Not a plain volatile field, and the reason is the one race a lock cannot close. A start
     * holds the monitor for as long as its migration runs, so a shutdown cannot take the monitor to
     * announce itself — it has to publish the terminal state without one. That leaves a window
     * between a start checking the state and a start acting on it, and a plain assignment at the end
     * of the start would simply overwrite the shutdown's announcement. A compare-and-set closes it:
     * the move to RUNNING succeeds only if the state is still the one the start decided on, and a
     * shutdown that got there first has already replaced it.
     */
    private final AtomicReference<State> state = new AtomicReference<>(State.AWAITING_STORE);
    private volatile boolean migrated;
    private volatile boolean active;

    /**
     * Whether the processor is consuming, tracked apart from the state.
     *
     * <p>Because {@code state} moves to {@code STOPPING} before the shutdown reaches the processor,
     * and "is it consuming" then has to be answered by something the shutdown has not already
     * overwritten.
     */
    private volatile boolean processorRunning;

    /** The probe's schedule, kept so shutdown can cancel it rather than merely outlive it. */
    private volatile ScheduledFuture<?> probeSchedule;

    /**
     * Latched once the gated start has completed, and never lowered again.
     *
     * <p>It answers "has this pod ever got as far as consuming", which is a readiness question. It
     * deliberately does not follow a later suspension: a pod that stopped intake because its store
     * went away is working correctly and waiting, the store's own contributor already reports that,
     * and rolling the pod would help nobody.
     */
    private volatile boolean gatedStartCompleted;

    public ConsumerLifecycleController(
            final ServiceBusProcessorClient processor,
            final ProcessedLogProbe storeProbe,
            final Supplier<Flyway> flyway,
            final ProcessingMetrics metrics,
            final ServiceBusHealthIndicator health,
            final Duration probeInterval) {
        this.processor = processor;
        this.storeProbe = storeProbe;
        this.flyway = flyway;
        this.metrics = metrics;
        this.health = health;
        this.probeInterval = probeInterval;
    }

    /** What intake is doing. */
    private enum State {

        /** Started, not yet consuming: the store has not answered once. */
        AWAITING_STORE,

        /** Consuming. */
        RUNNING,

        /** Stopped consuming because the store went away. */
        SUSPENDED,

        /**
         * The context is closing. Terminal: nothing may start intake from here, however far
         * through a start it already was.
         */
        STOPPING,

        /** Closed. */
        STOPPED
    }

    // --- lifecycle ---------------------------------------------------------------------------

    /**
     * Begins probing. Deliberately does <em>not</em> begin consuming.
     */
    @Override
    public void start() {
        active = true;
        LOG.info("Intake is gated on the processed log; probing every {} before consuming anything.",
                probeInterval);
        probeSchedule = transitions.scheduleWithFixedDelay(
                this::probe, 0, probeInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Closes intake for good: no more probing, no more starting, and the processor stopped.
     *
     * <p>The order matters, and the first step is the one that is easy to leave out. The terminal
     * state is published <strong>without taking the lock</strong>, because the thing most likely to
     * be happening at this moment is a start — and a start holds the lock for as long as its
     * migration runs. A shutdown that waited its turn would find the processor already started into
     * a context that is tearing down, consuming messages that the beans needed to record them are
     * no longer there to record. Publishing the state first lets that start see it and abandon
     * itself.
     *
     * <p>Then the schedule is cancelled rather than merely left to expire, the transitions are
     * drained, and the processor is stopped through the same serialised path everything else uses.
     * The final call is a deliberate second attempt: if the drain timed out, or the queued task
     * never ran, the processor must still be stopped, and doing it twice is a no-op.
     */
    @Override
    public void stop() {
        // Terminal state first, then the lifecycle flag. Both are volatile, and this order means
        // anything that observes the bean as no longer running also observes the state that tells
        // it not to start anything.
        // Never a downgrade: a second stop, or one arriving after the executor has finished, must
        // not move an already-closed controller back to closing.
        state.getAndUpdate(current -> terminal(current) ? current : State.STOPPING);
        active = false;
        cancelProbeSchedule();
        submitShutdown();
        transitions.shutdown();
        awaitTransitionsToFinish();
        shutdownIntake();
    }

    private void cancelProbeSchedule() {
        final ScheduledFuture<?> schedule = probeSchedule;
        if (schedule != null) {
            // Not interrupting: a probe part-way through a migration is left to finish, and it will
            // find the terminal state before it starts anything.
            schedule.cancel(false);
        }
    }

    /**
     * Puts the stop on the transition thread, so it is serialised with every other transition.
     */
    private void submitShutdown() {
        try {
            transitions.execute(this::shutdownIntake);
        } catch (RejectedExecutionException alreadyClosing) {
            LOG.debug("The transition thread was already closing; the stop runs inline instead.");
        }
    }

    /**
     * Stops the processor if it is consuming, and records that intake is closed. Idempotent.
     */
    private synchronized void shutdownIntake() {
        if (processorRunning) {
            processor.stop();
            processorRunning = false;
            LOG.info("Intake stopped for shutdown. queue={}", processor.getQueueName());
        }
        state.set(State.STOPPED);
    }

    private boolean closing() {
        return terminal(state.get());
    }

    private static boolean terminal(final State current) {
        return current == State.STOPPING || current == State.STOPPED;
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    /**
     * Whether the gated start has completed: the migration ran and the processor started.
     */
    public boolean intakeStarted() {
        return gatedStartCompleted;
    }

    private void awaitTransitionsToFinish() {
        try {
            if (!transitions.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                LOG.warn("The intake transition thread did not finish within {}; shutting down "
                        + "anyway.", SHUTDOWN_GRACE);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for the intake transition thread to finish.");
        }
    }

    // --- the store gate the listener uses ---------------------------------------------------------

    @Override
    public boolean storeAvailable() {
        return storeProbe.available();
    }

    /**
     * Asks for intake to stop, from wherever the outage was discovered.
     *
     * <p>Enqueued rather than done: the caller is a message callback, and stopping a processor from
     * inside one deadlocks. In-flight callbacks carry on while the processor drains — each meets the
     * same store outage, hands its own delivery back, and asks for the same thing, which is why the
     * request has to be idempotent rather than merely safe.
     */
    @Override
    public void suspendIntake() {
        try {
            transitions.execute(this::suspend);
        } catch (RejectedExecutionException afterShutdown) {
            // Asked for after the transition thread closed. There is nothing left to suspend, and
            // the caller is a broker callback that has just met a dead store: it has a delivery to
            // hand back and nothing useful to do with a failure from this call. Throwing at it
            // would turn a settled delivery into an unsettled one at the worst possible moment.
            // Checking a flag first would not do — the answer can change between the check and the
            // submission, which is precisely the window this catch closes.
            LOG.debug("Intake suspension requested after shutdown; there is nothing to suspend.");
        }
    }

    // --- transitions, all on the one thread ----------------------------------------------------

    /**
     * One probe: nothing to do while running, and otherwise the question that starts intake.
     *
     * <p>The failure is caught here and nowhere else, for one structural reason: a scheduled task
     * that throws is <strong>cancelled</strong>, so an exception escaping this method would end the
     * probing — and a service that has stopped asking whether its store is back never comes back.
     * It is reported at ERROR and the schedule survives; the next probe is the retry, which is
     * exactly the recovery this component exists to perform (constitution Principle VI: surfaced,
     * and acted on).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Deliberately total. Whatever ends a probe — an unreachable store, a migration that failed, a
    // processor that refused to start — the answer is the same and the schedule must outlive it.
    private void probe() {
        try {
            if (state.get() != State.RUNNING && !closing() && storeProbe.available()) {
                resume();
            }
        } catch (RuntimeException failed) {
            // Reported by type, not by text. What ends a start is routinely a driver or a
            // migration exception, and those quote connection URLs, statements and, on a parse
            // failure, the bytes they choked on. The type says what happened; the next probe says
            // whether it is still happening.
            LOG.error("Intake could not be started; the next probe will try again. type={}",
                    failed.getClass().getName());
        }
    }

    /**
     * Migrate if this is the first time, then consume.
     *
     * <p>Synchronised with {@link #suspend()} so the pair reads as one state machine, even though
     * the executor already serialises them. The guard is the state, so a resume that arrives while
     * already running does nothing.
     */
    private synchronized void resume() {
        final State from = state.get();
        if (from != State.RUNNING && !terminal(from)) {
            migrateOnce();
            // The gate is this move, and it happens BEFORE the processor is touched. Asking again
            // and then starting would leave the same window it was meant to close, only narrower:
            // a shutdown landing between the question and the answer would still be overwritten by
            // the assignment that followed the start. Here there is nothing to overwrite — either
            // the state is still what this start decided on, in which case it becomes RUNNING and a
            // shutdown that arrives from now on can see that and stop it, or a shutdown has already
            // replaced it and nothing starts at all.
            if (state.compareAndSet(from, State.RUNNING)) {
                startConsuming(from);
            } else {
                LOG.info("Intake is closing; the gated start was abandoned rather than completed.");
            }
        }
    }

    /**
     * The start itself, once the store has answered and the schema is in place.
     */
    private void startConsuming(final State from) {
        // Recorded before the call, and recorded separately from the state. Once the state has been
        // moved to STOPPING by a shutdown, a shutdown that asked "is the state RUNNING" would find
        // its own announcement and stop nothing — leaving a processor consuming into a context that
        // had closed. So the fact that there is a processor to stop is held on its own, and it is
        // set before the call rather than after it, so that it is true for the whole of the window
        // in which a processor might have been started. Stopping one that never managed to start is
        // a no-op.
        processorRunning = true;
        try {
            processor.start();
        } catch (RuntimeException refused) {
            processorRunning = false;
            // Given back only if this start still holds it: a shutdown that has moved the state on
            // must not be undone by a start that failed.
            state.compareAndSet(State.RUNNING, from);
            throw refused;
        }
        gatedStartCompleted = true;
        metrics.intakeResumed();
        // From here on, silence from the broker means something. Before it, this pod had not asked
        // the broker for anything and had no business reporting on it.
        health.recordIntakeStarted();
        if (from == State.SUSPENDED) {
            LOG.info("The processed log answered; intake resumed. queue={}",
                    processor.getQueueName());
        } else {
            LOG.info("The processed log answered; intake started. queue={}",
                    processor.getQueueName());
        }
    }

    /**
     * Stop consuming, and count it — once per outage, not once per delivery that noticed.
     *
     * <p>A suspension requested while already suspended is a no-op, which matters: with two
     * concurrent deliveries an outage produces two requests, and an incident counter that moved
     * twice for one outage would make every dashboard read wrong.
     */
    private synchronized void suspend() {
        if (state.compareAndSet(State.RUNNING, State.SUSPENDED)) {
            LOG.warn("The processed log is unreachable; stopping intake so the delivery budget is "
                    + "not spent on an outage of ours. queue={}", processor.getQueueName());
            processor.stop();
            processorRunning = false;
            metrics.intakeSuspended();
        }
    }

    /**
     * The deferred migration, run exactly once and before anything is consumed.
     *
     * <p>Not guarded by a try/catch: a migration that fails must not be followed by
     * {@code processor.start()}, and the probe will simply ask again at the next interval. The
     * failure is the executor's to report, and the pod stays unready meanwhile — which is the
     * honest state of a service whose schema is not there.
     */
    private void migrateOnce() {
        if (migrated) {
            return;
        }
        final Flyway migrations = flyway.get();
        if (migrations == null) {
            LOG.info("No Flyway configured; nothing to migrate before intake starts.");
        } else {
            LOG.info("Running the deferred schema migration before intake starts.");
            migrations.migrate();
        }
        migrated = true;
    }
}
