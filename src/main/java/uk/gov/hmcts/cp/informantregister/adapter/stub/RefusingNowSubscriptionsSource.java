package uk.gov.hmcts.cp.informantregister.adapter.stub;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;

/**
 * The subscriptions port until the reference-data adapter is written: it refuses, every time.
 *
 * <p><strong>Why this refuses rather than answering "nobody is subscribed".</strong> Those two are
 * indistinguishable downstream. An empty answer is a legitimate business outcome — the legacy files
 * the register with no recipients and carries on ({@code InformantRegisterSubscriptions/index.js:22-25})
 * — so a stub that answered it would let a real hearing produce a real {@code add-informant-register}
 * command addressed to nobody, POST it, and record the request COMPLETED. Nothing anywhere would say
 * the register had not been addressed; it would look like a quiet day. That is precisely the silent
 * loss this service exists to remove, and it is the failure mode the parity pack's pinning entry
 * {@code d03} was written to prevent.
 *
 * <p>Refusing is recoverable and visible: the delivery is handed back, the request is recorded
 * RETRYING and then FAILED, and the documented {@code FAILED} → replay path re-runs it once the real
 * adapter lands. Nothing is sent that would have to be unpicked.
 *
 * <p>It is only ever reached by a hearing that produced fragments, because the chain asks for
 * subscriptions only after the builder has found an authority to file a register for. A hearing with
 * no register in it still completes normally.
 */
public class RefusingNowSubscriptionsSource implements NowSubscriptionsSource {

    private static final Logger LOG =
            LoggerFactory.getLogger(RefusingNowSubscriptionsSource.class);

    /** {@inheritDoc} */
    @Override
    public JsonNode fetch(final LocalDate on, final CallerIdentity identity) {
        LOG.error("No now-subscriptions adapter is wired: the register cannot be addressed, so the "
                + "delivery is handed back rather than sent to nobody. queryDate={}", on);
        throw new ReferenceDataUnavailableException(ReasonCode.REFERENCE_DATA_UNAVAILABLE);
    }
}
