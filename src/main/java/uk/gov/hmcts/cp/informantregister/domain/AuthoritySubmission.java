package uk.gov.hmcts.cp.informantregister.domain;

import tools.jackson.databind.JsonNode;

/**
 * One prosecuting authority's share of a request, ready to be submitted.
 *
 * <p>The identifier is typed because this service owns it; the document is left as a tree because
 * this service does not. The outbound {@code add-informant-register} body is results-owned and its
 * shape is settled by the transformation story, so binding it to a record now would fix a contract
 * this increment has not been given. Principle IV's rule holds either way — what this service
 * <em>produces</em> is typed, and the document becomes typed the moment its shape is agreed.
 *
 * <p>No submission is ever built in this increment: with no transformation port the pipeline
 * produces an empty authority set, so the record exists to fix the port's signature rather than to
 * carry traffic.
 *
 * @param prosecutionAuthorityId the authority this output is for
 * @param document               the outbound document, as a canonical tree
 */
public record AuthoritySubmission(String prosecutionAuthorityId, JsonNode document) {
}
