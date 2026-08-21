package uk.gov.hmcts.cp.informantregister.application;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;

/**
 * Where the hearing payload for a request comes from.
 *
 * <p>One of the two ports the skeleton exists to define. The core owns this interface; the adapter
 * behind it is a logging stub in this increment and becomes the cache-with-query-fallback adapter in
 * a later story. Nothing here names a cache, a client or a transport, which is the whole point —
 * swapping the adapter must not reopen the pipeline.
 *
 * <p>The payload is returned as a canonical tree rather than a bound model: it is large, sparsely
 * populated and owned elsewhere, and binding it would silently discard every field this service does
 * not yet know about (constitution Principle IV).
 */
public interface HearingPayloadSource {

    /**
     * Obtains the hearing payload the request refers to.
     *
     * @param command the validated request
     * @return the payload, as a canonical tree
     * @throws PayloadUnavailableException if the payload cannot be obtained — always transient
     */
    JsonNode fetch(DistributionCommand command) throws PayloadUnavailableException;
}
