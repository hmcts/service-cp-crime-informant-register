package uk.gov.hmcts.cp.informantregister.domain;

/**
 * One addressee of the generated informant register document.
 *
 * <p>Recipients are optional on the command, and this service does not populate them: the design
 * rules record that letter-delivery recipients are ignored by this flow, and that oddity is ported
 * rather than corrected. The record exists because the contract declares the array, and a tree that
 * models only the fields today's pipeline fills would be a different contract from the one the
 * consumer enforces.
 *
 * @param recipientName     the addressee's name
 * @param emailAddress1     the primary address; required by the schema when a recipient is present
 * @param emailAddress2     a secondary address
 * @param emailTemplateName the template the notification leg renders with
 */
public record InformantRegisterRecipient(
        String recipientName,
        String emailAddress1,
        String emailAddress2,
        String emailTemplateName) {
}
