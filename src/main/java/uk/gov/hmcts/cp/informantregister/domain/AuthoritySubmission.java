package uk.gov.hmcts.cp.informantregister.domain;

import java.util.UUID;

/**
 * One prosecuting authority's share of a request, ready to be submitted.
 *
 * <p>Both halves are typed, and the document half deliberately so. Principle IV is not symmetrical:
 * a hearing payload crosses this service as a {@code JsonNode} because it is owned elsewhere and
 * must survive untouched, while <em>everything this service produces</em> — the
 * {@code add-informant-register} body included — is a typed record, so a field the service cannot
 * name is a field it cannot send under a contract that is {@code additionalProperties: false}. The
 * tree this component briefly held was a placeholder for a document type that had not been written
 * yet; {@link InformantRegisterDocument} is that type, so the placeholder is gone.
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
 * @param document               the outbound document, typed against the results-owned contract
 */
public record AuthoritySubmission(
        String source,
        UUID requestId,
        String prosecutionAuthorityId,
        InformantRegisterDocument document) {
}
