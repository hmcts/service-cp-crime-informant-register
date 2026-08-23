package uk.gov.hmcts.cp.informantregister.application;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * How a hearing payload becomes the command bodies that are sent for it.
 *
 * <p>The third port. Behind it sits the whole of the legacy transformation — the three activities
 * {@code SetInformantRegister}, {@code InformantRegisterSubscriptions} and
 * {@code OutboundInformantRegister} that {@code InformantRegisterOrchestrator} chains together
 * ({@code index.js:21-41}) — and the reference-data lookup that sits between the first two. The core
 * names none of it: it hands over a canonical hearing tree and receives typed documents.
 *
 * <p><strong>An empty list is a legitimate answer.</strong> A hearing with no prosecuting authority
 * to file a register for produces nothing, and the legacy's orchestrator skips the rest of the flow
 * for exactly that reason ({@code index.js:27}). The pipeline records that as {@code COMPLETED} with
 * the reason {@code no-authorities}; it is a business outcome, not an error, and it is
 * deviations-register entry 6.
 */
public interface RegisterTransformer {

    /**
     * Turns one hearing into one command body per prosecuting authority.
     *
     * @param hearing    the canonical hearing tree, read and never written
     * @param sharedTime the instant the hearing results were shared, as the wire carried it; may be
     *                   {@code null}, which the legacy resolves against the wall clock
     * @param identity   who the run is made as, carried through to the one call the transformation
     *                   makes outwards — the now-subscriptions read, which the legacy makes as the
     *                   sharing user ({@code ReferenceDataService.js:44})
     * @return one document per authority, in the order the legacy produces them; empty when the
     *         hearing has no register in it
     * @throws TransformationFailedException      where the legacy raises an error it then swallows —
     *                                            non-transient, because the payload reads the same
     *                                            on every delivery
     * @throws ReferenceDataUnavailableException  when the subscriptions the register is addressed
     *                                            with cannot be obtained — transient
     */
    List<InformantRegisterDocument> transform(
            JsonNode hearing, String sharedTime, CallerIdentity identity);
}
