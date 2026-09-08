package uk.gov.hmcts.cp.informantregister.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.CompletionReason;
import uk.gov.hmcts.cp.informantregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.GuardDecision;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.informantregister.domain.RunClaim;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.informantregister.observability.FaultSummary;

/**
 * One request, from the guard admitting it to the guard recording what happened.
 *
 * <p>The application core: it names no broker, no database and no HTTP client, and it is the only
 * place that knows the order the ports are called in. The transport adapter above it decides how a
 * delivery is settled; this decides what the delivery is worth settling as.
 *
 * <p><strong>A run that produces no authorities is a success.</strong> The transformation port
 * answers with one document per prosecuting authority, and a hearing that has no register in it
 * answers with none — which is what the legacy orchestrator's {@code if (informantRegisters)} guard
 * means ({@code index.js:27}). The submission port is then invoked once per member of an empty set,
 * which is not at all, and the request completes with {@code no-authorities}: a business outcome
 * recorded as such rather than an error (spec US1-2, FR-010; deviations-register entry 6).
 *
 * <p><strong>The run bounds itself.</strong> Before the ports are touched the deadline is fixed at
 * {@code informantregister.claim.processing-deadline} from now, and the run checks it before writing
 * an outcome. The deadline is strictly shorter than the claim lease, so a slow run stops itself
 * while its claim is still unambiguously its own; the alternative is a runner that discovers it has
 * been superseded only when its outcome write affects no rows — which is safe, but leaves the
 * request waiting for a redelivery it could have asked for a minute earlier (data-model invariant 8).
 * The check is against elapsed local time only: nothing here compares a JVM reading with a stored
 * timestamp, which is the multi-node skew the data model's single-time-authority rule exists to rule
 * out.
 *
 * <p><strong>A failure is read twice: is it worth retrying, and is there a retry left?</strong> The
 * first question is the ports' to answer, and they answer it in the exception — a transformation
 * that cannot read the payload and a 4xx contract rejection are both {@code NON_TRANSIENT}, and a
 * non-transient failure is recorded FAILED and parked immediately, whatever the delivery count says.
 * Handing one back instead would spend the whole delivery budget re-reading a payload that reads the
 * same every time and park it at the end under {@code DELIVERY_LIMIT_EXHAUSTED} — a reason that
 * tells support the service ran out of tries rather than that the payload was unusable
 * (`design_rules.md`, "Processing State Machine").
 *
 * <p>Only a transient failure asks the second question. With deliveries remaining it is recorded
 * RETRYING and the delivery is handed back; on the final permitted delivery the same failure is
 * recorded FAILED, with the identity of the delivery that exhausted the budget, and the message is
 * parked. The transport adapter reads that fact from the delivery and carries it in, because the
 * processed log cannot know it — the budget belongs to the message, not to the request.
 *
 * <p>Payload unavailability is transient by construction and a deadline is not a fault at all, so
 * neither is ever parked for being unretryable, and a failure nothing anticipated is treated as
 * transient because "unknown" is not the same as "hopeless".
 */
public class DistributionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(DistributionPipeline.class);

    private final IdempotencyGuard guard;
    private final HearingPayloadSource payloadSource;
    private final RegisterTransformer transformer;
    private final RegisterSubmissionClient submissionClient;
    private final ProcessingMetrics metrics;
    private final Clock clock;
    private final Duration processingDeadline;

    /** Creates the pipeline over its ports; every dependency is an application-owned interface. */
    public DistributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final RegisterTransformer transformer,
            final RegisterSubmissionClient submissionClient,
            final ProcessingMetrics metrics,
            final Clock clock,
            final Duration processingDeadline) {
        this.guard = guard;
        this.payloadSource = payloadSource;
        this.transformer = transformer;
        this.submissionClient = submissionClient;
        this.metrics = metrics;
        this.clock = clock;
        this.processingDeadline = processingDeadline;
    }

    /**
     * Runs the request through the guard and, if it is admitted, through the ports.
     *
     * @param command  the validated request
     * @param delivery who is delivering it, and under which broker identity
     * @return what the delivery should do next — a settlement, never a run
     */
    public GuardDecision process(final DistributionCommand command, final DeliveryIdentity delivery) {
        final GuardDecision admission = guard.admit(command, delivery);
        final GuardDecision decision;
        if (admission instanceof GuardDecision.Run admitted) {
            decision = runUnder(command, admitted.claim(), delivery.finalPermittedDelivery());
        } else {
            // Already completed, contested, or a collision: the guard has decided, and a run would
            // either duplicate work or overwrite a record that belongs to a different request.
            decision = admission;
        }
        return decision;
    }

    /**
     * The run itself, with every failure it can meet turned into an outcome.
     *
     * <p>The second catch is total on purpose: this frame holds the claim, and it is the only frame
     * that does. A failure that escaped it would leave {@code claim_owner} live for the rest of the
     * lease, so every redelivery would bounce off {@code CLAIM_NOT_ACQUIRED} until the broker parked
     * the message under its own reason with no FAILED record behind it — the silent parking the
     * state machine exists to prevent. It is a catch-and-record, not a catch-and-ignore: the failure
     * is reported at ERROR, classified, and written to the processed log before the delivery is
     * settled. A store that dies inside the recording write throws out of the catch block itself,
     * which is correct — nothing is recordable during a store outage, and the transport adapter's
     * own handling takes over.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private GuardDecision runUnder(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance) {
        GuardDecision outcome;
        try {
            outcome = runToOutcome(command, claim, lastChance);
        } catch (PayloadUnavailableException unavailable) {
            outcome = failed(claim, unavailable.classification(), unavailable.reason(), lastChance);
        } catch (TransformationFailedException unreadable) {
            // The detail is the only thing that distinguishes one transformation refusal from
            // another: the reason code is TRANSFORMATION_FAILED for all sixteen of them. It is
            // written by this service, in this service's vocabulary — see the accessor's comment.
            LOG.error("Hearing could not be transformed. source={} requestId={} detail={}",
                    claim.source(), claim.requestId(), unreadable.detail());
            outcome = failed(claim, unreadable.classification(), unreadable.reason(), lastChance);
        } catch (ReferenceDataUnavailableException unaddressable) {
            // The register was built; what is missing is who it goes to. The legacy answers that
            // with an empty recipient list and POSTs anyway — a register that reaches nobody, with
            // nothing recording the outage (deviations-register entry 14, parity pin d03).
            outcome = failed(
                    claim, unaddressable.classification(), unaddressable.reason(), lastChance);
        } catch (SubmissionFailedException submission) {
            // Both of these say whether they are worth retrying, so they are asked rather than
            // assumed: a payload the transformation cannot read reads the same on every delivery,
            // a refused body is refused on every delivery, and the delivery budget is finite.
            outcome = failed(claim, submission.classification(), submission.reason(), lastChance);
        } catch (RuntimeException unexpected) {
            LOG.error("Run failed unexpectedly; recording it so the claim is released. "
                            + "source={} requestId={} type={}",
                    claim.source(), claim.requestId(), FaultSummary.typeChain(unexpected));
            outcome = failed(claim, FailureClassification.TRANSIENT,
                    ReasonCode.UNEXPECTED_FAILURE, lastChance);
        }
        return outcome;
    }

    private GuardDecision runToOutcome(
            final DistributionCommand command, final RunClaim claim, final boolean lastChance) {
        // Two readings, and the log is built from those rather than taking its own.
        //
        // Instrumentation that reads the clock again is instrumentation that perturbs what it
        // measures: the deadline branch below is decided on a clock reading, and a timing call
        // placed between the two would report a different elapsed time from the one the decision
        // was made on — and, under a clock that advances per read, would change the decision. The
        // per-phase durations that this costs are recoverable anyway, because every line in the
        // log carries a timestamp; what timestamps cannot give is the relationship to the
        // deadline, which is why elapsed and remaining are stated explicitly at the point the
        // deadline is tested.
        final Instant startedAt = clock.instant();
        final Instant deadline = startedAt.plus(processingDeadline);

        final JsonNode payload = payloadSource.fetch(command);
        LOG.info("Hearing payload obtained. source={} requestId={} hearingId={} topLevelFields={}",
                command.source(), command.requestId(), command.hearingId(), payload.size());

        final List<AuthoritySubmission> submissions = submissionsFor(command, payload);

        final Instant transformedAt = clock.instant();
        final long elapsedMs = Duration.between(startedAt, transformedAt).toMillis();

        final GuardDecision outcome;
        // Strictly before, so the deadline is a bound that is *reached* rather than passed: a run
        // standing exactly on it has already used the time its claim guarantees and may not write a
        // completion. `isAfter` on the other side of this branch would let that one instant through.
        if (transformedAt.isBefore(deadline)) {
            // The budget left when the submissions start is the leading indicator for a run
            // creeping toward the deadline: by the time PROCESSING_DEADLINE_EXCEEDED is recorded
            // the run is already lost, and this is the line that saw it coming.
            LOG.info("Submitting registers. source={} requestId={} authorities={} "
                            + "elapsedMs={} deadlineRemainingMs={}",
                    claim.source(), claim.requestId(), submissions.size(), elapsedMs,
                    Duration.between(transformedAt, deadline).toMillis());
            outcome = submitWithin(claim, submissions, deadline, elapsedMs, lastChance);
        } else {
            // Said separately from the generic failure line below, because "how far over, and with
            // how much work still outstanding" is the whole question and the bounded reason code
            // cannot carry it.
            LOG.error("The processing deadline passed before any register was submitted. "
                            + "source={} requestId={} authorities={} elapsedMs={} deadlineMs={}",
                    claim.source(), claim.requestId(), submissions.size(), elapsedMs,
                    processingDeadline.toMillis());
            outcome = failed(claim, FailureClassification.TRANSIENT,
                    ReasonCode.PROCESSING_DEADLINE_EXCEEDED, lastChance);
        }
        return outcome;
    }

    /**
     * POSTs each authority's register, stopping if the deadline arrives part-way through.
     *
     * <p>The deadline is tested before <em>every</em> submission, not once before the loop. One
     * authority can legitimately spend {@code results.max-attempts} x (connect + read) plus its
     * capped back-offs, and the payload fetch and the now-subscriptions read may already have
     * consumed most of the budget before the first POST is even attempted — so a loop entered with
     * seconds to spare can run minutes past the deadline and, with it, past the claim lease. That
     * is not a slow run: it is <em>two</em> runs. Once the lease lapses a redelivery reclaims the
     * request and is granted every authority not yet {@code POSTED}, so both runners POST, and
     * {@code add-informant-register} is not idempotent. The duplicate register row stops being the
     * rare crash-window case the delivery guarantee owns and becomes load-dependent.
     *
     * <p>Stopping is therefore the safe outcome and not a lost one. It is transient: the
     * authorities already POSTed are recorded as such and skipped on the redelivery, so only the
     * outstanding ones are repeated, and the run that resumes them starts with a full budget.
     *
     * <p>The first iteration re-reads a clock the caller has just read. That is deliberate rather
     * than redundant: the caller's test guards the phase — including a run that produced no
     * authorities at all, which has nothing to iterate and must still not record a completion it no
     * longer holds the claim to write — while this one guards each POST.
     *
     * @param claim       the claim this run holds
     * @param submissions the authorities to POST, in the order they are to be made
     * @param deadline    the instant the run must have finished by
     * @param elapsedMs   time spent up to the end of the transformation, for the completion line
     * @param lastChance  whether the queue's delivery budget ends with this delivery
     * @return the recorded outcome — a completion, or the transient failure that stopped it
     */
    private GuardDecision submitWithin(
            final RunClaim claim,
            final List<AuthoritySubmission> submissions,
            final Instant deadline,
            final long elapsedMs,
            final boolean lastChance) {

        int submitted = 0;
        for (final AuthoritySubmission submission : submissions) {
            final Instant beforeSubmission = clock.instant();
            // Strictly before, on the same reasoning as the phase test above: a run standing
            // exactly on its deadline has used the time its claim guarantees and may not spend more.
            if (!beforeSubmission.isBefore(deadline)) {
                // Said separately from the generic failure line, and separately from the
                // never-started case: how many registers are already out is what decides whether
                // this hearing can have produced a duplicate, and the bounded reason code cannot
                // carry it.
                LOG.error("The processing deadline passed part-way through submission. "
                                + "source={} requestId={} submitted={} outstanding={} "
                                + "elapsedMs={} overrunMs={}",
                        claim.source(), claim.requestId(), submitted,
                        submissions.size() - submitted, elapsedMs,
                        Duration.between(deadline, beforeSubmission).toMillis());
                return failed(claim, FailureClassification.TRANSIENT,
                        ReasonCode.PROCESSING_DEADLINE_EXCEEDED, lastChance);
            }
            submissionClient.submit(submission);
            submitted++;
        }
        return completed(claim, submissions, elapsedMs);
    }

    /**
     * Turns the fetched payload into one submission per prosecuting authority.
     *
     * <p>The payload the source answers with is a wrapper, not a hearing — the {@code INT_} cache
     * document is {@code {isReshare, hearingDay, sharedTime, hearing}} and the query-API answer is
     * {@code {hearing, sharedTime}} — and this seam is the orchestrator-equivalent that opens it:
     * {@code InformantRegisterOrchestrator/index.js:21-24} hands its first activity
     * {@code hearingResultedObj.hearing} under {@code hearingResultedObj.sharedTime}, both read
     * from the fetched document and neither from the queue message. The command's own
     * {@code sharedTime} keeps the jobs that belong to this service's closed contract — the
     * request fingerprint and the processed-log row — but the register is stamped with the
     * payload's, exactly as the legacy stamps it.
     *
     * <p>A wrapper whose {@code hearing} is missing or {@code null} is refused as unreadable: the
     * legacy throws there ({@code SetInformantRegister/index.js:29} reads {@code hearingObj.id})
     * and its orchestrator swallows the run, which is precisely the class of silent loss
     * deviations-register entry 7 converts to a non-transient refusal. A missing
     * {@code sharedTime} is not an error — the legacy passes {@code undefined} through and the
     * date service reads its clock ({@code HearingDates}, "absent input means now").
     *
     * <p>Order is carried, never re-derived: the documents come back in the order the legacy produces
     * its fragments, and each becomes exactly one submission in that order. Nothing downstream sorts,
     * so which authority is POSTed first is decided here and nowhere else.
     *
     * <p>It is also where the run's caller is fixed. The command names the user who shared the
     * results, or names nobody; either way the answer is settled once here and given to the
     * transformation and to every submission, so the now-subscriptions read and all the POSTs of one
     * run are made as the same caller. The payload read is made as that caller too — it is given the
     * command itself, so it reads the same field.
     *
     * @param command the request being run
     * @param payload the wrapped hearing payload the source answered with
     * @return the submissions, in the order they are to be made
     */
    private List<AuthoritySubmission> submissionsFor(
            final DistributionCommand command, final JsonNode payload) {

        // Resolved once, from the command, and handed to everything that makes a call outwards. The
        // legacy resolves it once too — the trigger copies the envelope's userId into the
        // orchestration input and every activity is given that same value
        // (InformantRegisterOrchestrator/index.js:13,31,46) — and "once" is the part that matters:
        // a register read as one caller and posted as another is attributable to nobody.
        final CallerIdentity identity = CallerIdentity.of(command);

        return transformer.transform(hearingOf(payload), sharedTimeOf(payload), identity).stream()
                .map(document -> new AuthoritySubmission(
                        command.source(),
                        command.requestId(),
                        document.prosecutionAuthorityId().toString(),
                        document,
                        identity))
                .toList();
    }

    /**
     * The wrapper's {@code hearing} member — the node the legacy's activities receive.
     *
     * <p>Missing or {@code null} is a refusal, not a shrug: the legacy's first activity throws on
     * it ({@code SetInformantRegister/index.js:29}) and the orchestrator's catch-all turns that
     * into a silent success, which is the swallow entry 7 of the deviations register replaces
     * with a parked, replayable failure. Any other shape is passed through untouched — the
     * builder's own guard answers a hearing that carries no cases, exactly as the legacy's does.
     *
     * @param payload the wrapped payload the source answered with
     * @return the hearing node
     */
    private static JsonNode hearingOf(final JsonNode payload) {
        final JsonNode hearing = payload.path("hearing");
        if (hearing.isMissingNode() || hearing.isNull()) {
            throw new TransformationFailedException("payload carries no hearing");
        }
        return hearing;
    }

    /**
     * The wrapper's {@code sharedTime} as scalar text, {@code null} when the wrapper carries none,
     * or a refusal for a shape no readable register date can come out of.
     *
     * <p>An <em>absent</em> member is not an error: the legacy hands {@code undefined} straight
     * through ({@code InformantRegisterOrchestrator/index.js:23}) and
     * {@code moment.tz(undefined, zone)} is the current time, which {@code HearingDates}
     * reproduces from its clock. An explicit {@code null} is a different value to moment —
     * {@code moment.tz(null, zone)} is an <em>Invalid Date</em>, rendered {@code "Invalid dateZ"}
     * into a {@code date-time}-typed component, which registered deviation entry 10 refuses at the
     * typed boundary; it is refused here with the same classification and reason rather than
     * manufacturing a carrier string to fail on later. A container is refused on the same terms:
     * neither source writes one, it reads identically on every delivery, and letting Jackson's
     * {@code asString()} throw instead would misclassify it as a transient failure and spend the
     * whole delivery budget re-reading it. Any other scalar passes through as its text form; a
     * number diverges from moment's epoch-millis reading there, which is the entry-13 family of
     * date forms this port does not read — it renders {@code "Invalid dateZ"} and is refused at
     * the typed boundary, never silently reinterpreted.
     *
     * @param payload the wrapped payload the source answered with
     * @return the shared time text, or {@code null} when the wrapper has no such member
     */
    private static String sharedTimeOf(final JsonNode payload) {
        final JsonNode sharedTime = payload.path("sharedTime");
        if (sharedTime.isNull() || sharedTime.isContainer()) {
            throw new TransformationFailedException("payload sharedTime is not scalar text");
        }
        return sharedTime.isMissingNode() ? null : sharedTime.asString();
    }

    /**
     * Records the run's success, and counts it only if the guard accepted the write.
     *
     * <p>A superseded runner's completion affects no rows and comes back as an abandon; counting it
     * as a completed request would report work that was never recorded.
     *
     * <p>Both completions are successes and the reason says which. A hearing that legitimately
     * produces no authorities ends {@code COMPLETED} with {@code no-authorities} — a business
     * outcome, not an error, and the shape {@code design_rules.md} requires. Leaving the reason blank
     * for the other case would make "nothing was sent" and "everything was sent" the same row to
     * anybody reading the processed log.
     */
    private GuardDecision completed(final RunClaim claim,
            final List<AuthoritySubmission> submissions, final long elapsedMs) {

        final CompletionReason reason = submissions.isEmpty()
                ? CompletionReason.NO_AUTHORITIES
                : CompletionReason.AUTHORITIES_SUBMITTED;
        final GuardDecision outcome = guard.recordCompletion(claim, reason);
        if (outcome instanceof GuardDecision.Complete) {
            LOG.info("Run finished. source={} requestId={} authorities={} reason={} elapsedMs={}",
                    claim.source(), claim.requestId(), submissions.size(), reason.value(),
                    elapsedMs);
            metrics.requestSettled(RequestOutcome.COMPLETED);
        }
        return outcome;
    }

    /**
     * Records a failed run — loudly, and with a bounded reason rather than whatever the layer
     * beneath had to say about it.
     *
     * <p><strong>A failure the throw site classified {@code NON_TRANSIENT} is parked here and
     * now</strong>, whatever the delivery budget says: no redelivery can change it — the same
     * payload reads the same way, the same bytes meet the same refusal — so the delivery count is
     * not consulted at all, the remaining deliveries would buy nothing and would delay by four
     * back-offs the dead-letter support acts on, and the row carries the reason the port named
     * rather than an exhaustion the service never reached (design rules, "Processing State
     * Machine").
     *
     * <p>A transient failure means two different things depending on whether the queue will deliver
     * the message again. With deliveries remaining it is recorded RETRYING and the delivery is handed
     * back. On the final permitted delivery it is recorded FAILED, in the transaction that stamps the
     * identity of the delivery that exhausted the budget onto the row, and the message is parked
     * where support can see it. Retry exhaustion is judged by that delivery count alone and never by
     * the cumulative attempt count, which is a lifetime tally and would park a replayed request on
     * its first failure (spec FR-004, FR-009).
     *
     * <p>The terminal outcome is counted only once the guard has accepted the write: a superseded
     * runner's parking affects no rows and comes back as a hand-back, and counting it would report a
     * request parked that is still being worked on by somebody else.
     */
    private GuardDecision failed(
            final RunClaim claim,
            final FailureClassification classification,
            final ReasonCode reason,
            final boolean lastChance) {
        LOG.error("Pipeline run failed. source={} requestId={} classification={} reason={} "
                        + "finalPermittedDelivery={}",
                claim.source(), claim.requestId(), classification.label(), reason.code(), lastChance);
        metrics.pipelineFailed(classification);

        return switch (classification) {
            case NON_TRANSIENT -> parked(guard.recordNonTransientFailure(claim, reason));
            case TRANSIENT -> lastChance
                    ? parked(guard.recordExhaustion(claim, reason))
                    : guard.recordTransientFailure(claim, reason);
        };
    }

    /** Counts a parking the guard accepted, and only one it accepted. */
    private GuardDecision parked(final GuardDecision outcome) {
        if (outcome instanceof GuardDecision.DeadLetter) {
            metrics.requestSettled(RequestOutcome.FAILED);
        }
        return outcome;
    }
}
