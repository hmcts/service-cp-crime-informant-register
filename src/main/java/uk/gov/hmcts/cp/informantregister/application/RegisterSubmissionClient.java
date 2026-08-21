package uk.gov.hmcts.cp.informantregister.application;

import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;
import uk.gov.hmcts.cp.informantregister.domain.SubmissionFailedException;

/**
 * Where a per-authority register output is submitted.
 *
 * <p>The second of the two ports. The adapter behind it is a logging stub in this increment and
 * becomes the {@code add-informant-register} adapter in a later story; the core names no HTTP type.
 *
 * <p>The happy path of this increment invokes it <strong>zero</strong> times: with no transformation
 * port the pipeline produces an empty authority set, so a submission stub that is never called is
 * the intended shape and not a missing step.
 */
public interface RegisterSubmissionClient {

    /**
     * Submits one authority's output.
     *
     * @param submission the authority and its outbound document
     * @throws SubmissionFailedException if the submission does not succeed; the exception carries
     *                                   whether a redelivery could change that
     */
    void submit(AuthoritySubmission submission) throws SubmissionFailedException;
}
