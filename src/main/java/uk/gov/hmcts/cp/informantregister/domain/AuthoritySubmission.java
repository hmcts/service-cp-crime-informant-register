package uk.gov.hmcts.cp.informantregister.domain;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One prosecuting authority's share of a request, ready to be submitted.
 *
 * <p>The identifiers are typed because this service owns them; the document is left as a tree
 * because this service does not. The outbound {@code add-informant-register} body is results-owned
 * and its shape is settled by the transformation story, so binding it to a record here would fix a
 * contract this record has not been given. Principle IV's rule holds either way — what this service
 * <em>produces</em> is typed, and {@code InformantRegisterDocument} is that type; a submission
 * carries whatever tree the transformation produced from it.
 *
 * <p><strong>Why the request's key travels with the authority.</strong> A submission is recorded in
 * {@code processed_output} before it is sent, and that row is keyed
 * {@code (source, request_id, prosecution_authority_id)} with a foreign key back to the request it
 * belongs to. An authority identifier on its own cannot name that row, so a submission that carried
 * only the authority would make the per-authority log unwritable — and without it a redelivery after
 * three of five authorities succeeded would re-send the three that worked.
 *
 * @param source                 the request's key, part 1
 * @param requestId              the request's key, part 2
 * @param prosecutionAuthorityId the authority this output is for
 * @param document               the outbound document, as a canonical tree
 */
public record AuthoritySubmission(
        String source, UUID requestId, String prosecutionAuthorityId, JsonNode document) {
}
