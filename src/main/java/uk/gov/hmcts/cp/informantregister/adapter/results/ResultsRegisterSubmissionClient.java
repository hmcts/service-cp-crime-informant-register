package uk.gov.hmcts.cp.informantregister.adapter.results;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedOutputRepository;

/**
 * The submission port, wired to the Results command API and to the per-authority log.
 *
 * <p>The order matters more than anything else in this class, because
 * {@code add-informant-register} is not idempotent — a second POST makes a second register row:
 *
 * <ol>
 *   <li><strong>Claim the row, before anything is sent.</strong> The same statement asks two
 *       questions at once: may this delivery send, and record that it is about to. A claim the
 *       database refuses means the authority is already POSTED, so this delivery skips it and
 *       partial progress across several authorities survives a redelivery or a replay.</li>
 *   <li><strong>POST.</strong> The transport's retry policy applies; what comes back out is either
 *       nothing or a classified failure.</li>
 *   <li><strong>Record the outcome.</strong> A failure is written down <em>before</em> it is
 *       rethrown, so the log never shows a submission in flight that nothing is going to finish.</li>
 * </ol>
 *
 * <p>Writing the row first is what makes an unknown outcome survivable. A POST that times out leaves
 * a PENDING row carrying the digest of exactly the bytes that were attempted, so the next delivery
 * re-sends and reconciliation can tell whether the body changed. Writing it afterwards would lose
 * precisely the case the evidence exists for.
 *
 * <p><strong>The guarantee, stated honestly.</strong> At-most-once submission in normal operation,
 * redeliveries and replays included; across a crash in the instant between an accepted POST and the
 * row being marked POSTED, at-least-once. The duplicate is absorbed downstream exactly like a
 * re-share. Strict at-most-once needs Results-side idempotency, which the frozen contract does not
 * offer, and nothing here claims otherwise.
 *
 * <p>The document is never logged, at any level. It is a register naming defendants and their
 * addresses; identifiers are all a log line carries.
 */
public class ResultsRegisterSubmissionClient implements RegisterSubmissionClient {

    private static final Logger LOG = LoggerFactory.getLogger(ResultsRegisterSubmissionClient.class);
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final ProcessedOutputRepository outputs;
    private final ResultsCommandGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * Creates the adapter over the per-authority log and the Results transport.
     *
     * @param outputs      the {@code processed_output} statements
     * @param gateway      the {@code add-informant-register} transport, with its retry policy
     * @param objectMapper the shared mapper, so what is sent is serialised exactly as everything else
     */
    public ResultsRegisterSubmissionClient(
            final ProcessedOutputRepository outputs,
            final ResultsCommandGateway gateway,
            final ObjectMapper objectMapper) {
        this.outputs = outputs;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public void submit(final AuthoritySubmission submission) {
        final byte[] body = objectMapper.writeValueAsBytes(submission.document());
        final String digest = digestOf(body);

        final boolean maySend = outputs.claimPending(
                UUID.randomUUID(),
                submission.source(),
                submission.requestId(),
                submission.prosecutionAuthorityId(),
                digest);

        if (!maySend) {
            LOG.info("Authority already posted for this request; skipping. source={} requestId={} "
                            + "authority={}",
                    submission.source(), submission.requestId(), submission.prosecutionAuthorityId());
            return;
        }

        try {
            gateway.post(body, submission.identity());
        } catch (SubmissionFailedException failure) {
            // Caught to record, never to absorb: the row is moved to FAILED and the same exception
            // continues, carrying the classification the pipeline settles the delivery on.
            recorded(outputs.recordFailed(
                    submission.source(), submission.requestId(), submission.prosecutionAuthorityId()),
                    "FAILED", submission);
            throw failure;
        }

        recorded(outputs.recordPosted(
                submission.source(), submission.requestId(), submission.prosecutionAuthorityId()),
                "POSTED", submission);
        LOG.info("Authority submitted. source={} requestId={} authority={}",
                submission.source(), submission.requestId(), submission.prosecutionAuthorityId());
    }

    /**
     * Checks that the outcome write this delivery depended on actually landed.
     *
     * <p>The affected-row count is the decision here as it is everywhere else in the processed log,
     * and it is asked rather than discarded. A claim was granted moments earlier, so the only way an
     * outcome write can affect nothing is that a delivery this one overlapped with reached the row
     * first and POSTED it — which means two runners were working the same request, and the losing
     * one's view of what happened is not the durable one.
     *
     * <p>It is reported and not thrown. The POST has already happened either way, and turning a
     * disagreement about the evidence into a failure would either re-send a body that was accepted
     * or hide a failure that was not. The register's fate is decided by the exception the caller is
     * already carrying, or by its absence; this line is how an overlap becomes visible instead of
     * silent.
     */
    private static void recorded(
            final boolean written, final String status, final AuthoritySubmission submission) {
        if (!written) {
            LOG.error("Outcome write affected no row; an overlapping delivery reached it first. "
                            + "source={} requestId={} authority={} intendedStatus={}",
                    submission.source(), submission.requestId(),
                    submission.prosecutionAuthorityId(), status);
        }
    }

    /**
     * SHA-256 of exactly the bytes that go on the wire.
     *
     * <p>Over the serialised body rather than over the document object, because the digest is
     * reconciliation evidence: a digest of something other than what was sent is worse than no digest
     * at all.
     */
    private static String digestOf(final byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(DIGEST_ALGORITHM).digest(body));
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java platform, so this cannot happen on a running JVM; if
            // it ever did, no submission could be recorded safely and failing loudly is the only
            // honest response.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", unavailable);
        }
    }
}
