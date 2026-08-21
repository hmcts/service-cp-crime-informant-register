package uk.gov.hmcts.cp.informantregister.adapter.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.domain.AuthoritySubmission;

/**
 * A submission client that submits nothing, and says so on every call.
 *
 * <p>It is never called in this increment: the pipeline produces no authorities, so there is nothing
 * to submit. That is deliberate — the port exists so the later story can attach the
 * {@code add-informant-register} adapter without reopening the pipeline — and a log line that never
 * appears is the honest evidence for it.
 *
 * <p>The submission itself is not logged, only the authority it was for. The outbound document is a
 * register fragment for named individuals, and whole documents are not logged at any level in a
 * deployed environment (constitution Principle VII).
 */
public class StubRegisterSubmissionClient implements RegisterSubmissionClient {

    private static final Logger LOG = LoggerFactory.getLogger(StubRegisterSubmissionClient.class);

    @Override
    public void submit(final AuthoritySubmission submission) {
        LOG.info("STUB submission client invoked: nothing is submitted. authority={}",
                submission.prosecutionAuthorityId());
    }
}
