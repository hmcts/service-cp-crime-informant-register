package uk.gov.hmcts.cp.informantregister.adapter.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.config.InformantRegisterProperties;
import uk.gov.hmcts.cp.informantregister.config.PayloadFailureMode;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;

/**
 * A payload source that fetches nothing, and says so on every call.
 *
 * <p>The real adapter — the hearing payload cache, with the query-side fallback — arrives with a
 * later story. Until it does this is the deployed adapter, which is the agreed shape of the walking
 * skeleton rather than an oversight, so it logs at INFO on every invocation: a stub that is quiet is
 * a stub somebody will mistake for the real thing.
 *
 * <p>It is also where spec FR-009's simulated transient failure lives. The switch is a configuration
 * property read once at construction — never a field in the message and never an HTTP endpoint,
 * because either of those would be a production fault-injection surface on a service whose whole
 * purpose is not losing work.
 */
public class StubHearingPayloadSource implements HearingPayloadSource {

    private static final Logger LOG = LoggerFactory.getLogger(StubHearingPayloadSource.class);

    /**
     * A placeholder, deliberately carrying nothing that resembles hearing content: this increment
     * handles no defendant data at all, and a stub payload that looked like one would invite a test
     * to start depending on its shape.
     */
    private static final String PLACEHOLDER = """
            {"stub":true,"note":"no hearing payload is fetched in this increment"}
            """;

    private final PayloadFailureMode failureMode;
    private final JsonNode placeholder;

    public StubHearingPayloadSource(
            final InformantRegisterProperties properties, final ObjectMapper objectMapper) {
        this.failureMode = properties.stub().payloadFailureMode();
        this.placeholder = objectMapper.readTree(PLACEHOLDER);
    }

    @Override
    public JsonNode fetch(final DistributionCommand command) {
        LOG.info("STUB payload source invoked: nothing is fetched, a placeholder is returned. "
                        + "source={} requestId={} hearingId={} hearingDay={} failureMode={}",
                command.source(), command.requestId(), command.hearingId(), command.hearingDay(),
                failureMode);
        if (failureMode == PayloadFailureMode.TRANSIENT) {
            LOG.warn("STUB payload source is configured to fail transiently. source={} requestId={}",
                    command.source(), command.requestId());
            throw new PayloadUnavailableException(ReasonCode.PIPELINE_TRANSIENT_FAILURE);
        }
        // The same immutable tree on every call. Nothing may mutate a node it did not construct, so
        // sharing it is safe and building a fresh copy per request would be waste.
        return placeholder;
    }
}
