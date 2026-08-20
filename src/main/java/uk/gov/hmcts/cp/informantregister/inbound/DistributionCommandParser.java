package uk.gov.hmcts.cp.informantregister.inbound;

import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;

/**
 * Turns a raw message body into a validated {@link DistributionCommand}, or refuses it with a
 * bounded reason.
 *
 * <p>Validation is explicit rather than schema-driven at runtime: the committed draft-07 schema is
 * the contract's source of truth, and the contract tests hold this parser to it case for case. That
 * keeps one validation implementation on the hot path and lets a rejection say precisely what was
 * wrong, which is what a dead-letter description is for.
 */
public class DistributionCommandParser {

    private final ObjectMapper objectMapper;

    public DistributionCommandParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates and converts a message body.
     *
     * @param body the raw message body
     * @return the validated command
     * @throws uk.gov.hmcts.cp.informantregister.domain.ContractValidationException if the body does
     *         not satisfy the inbound contract
     */
    public DistributionCommand parse(final String body) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    protected ObjectMapper objectMapper() {
        return objectMapper;
    }
}
