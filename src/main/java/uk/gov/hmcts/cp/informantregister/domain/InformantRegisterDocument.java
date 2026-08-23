package uk.gov.hmcts.cp.informantregister.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The body of one {@code add-informant-register} command — one prosecuting authority's register.
 *
 * <p>This is the one tree in the service that is modelled as typed records rather than left as a
 * Jackson tree, because it is the only JSON this service <em>produces</em> (constitution Principle
 * IV). Inbound hearing payloads stay canonical trees; what goes out is typed, so a field this
 * service cannot name is a field it cannot send.
 *
 * <p>The contract is owned by {@code cpp-context-results} and is <strong>closed</strong>:
 * {@code additionalProperties: false} at every level of the tree, on all nine schema files. Nothing
 * may be added, renamed or widened here — a change to this shape is a change to somebody else's
 * contract and is raised with Results first. Canonical schema:
 * {@code cpp-context-results/results-json/src/main/resources/json/schema/informantRegisterDocument/
 * informantRegisterDocumentRequest.json}, reached from
 * {@code results.add-informant-register.json} in the command API's RAML. Byte-identical copies are
 * committed under {@code src/test/resources/contracts/results/} and this tree is asserted against
 * them.
 *
 * <p><strong>How the component types were chosen.</strong> One rule, applied everywhere: each
 * component takes the type the consumer's own generated binding gives it — the results-side
 * {@code uk.gov.justice.results.courts.informantRegisterDocument} classes. So the two timestamps are
 * {@link ZonedDateTime}, the three identifiers are {@link UUID}, {@code orderIndex} is an
 * {@code Integer}, and every other leaf in the tree is a {@code String}, including the ones the
 * schema marks {@code format: date} or {@code format: time}. Choosing per field on how the name
 * reads would have been guesswork; matching the binding that will actually deserialise the body is
 * not.
 *
 * <p>The two timestamps are pinned to a textual shape by annotation rather than left to a mapper
 * feature. {@code WRITE_DATES_AS_TIMESTAMPS} is a global switch, and a body that silently became a
 * pair of epoch numbers because somebody customised a mapper elsewhere would be rejected by the
 * consumer's schema, not by anything here.
 *
 * <p>Absent components are omitted from the wire rather than written as {@code null}: every optional
 * property declares a JSON type, so a null would violate the schema, and every optional array
 * declares {@code minItems: 1}, so an empty array would too. {@code @JsonInclude(NON_NULL)} on every
 * record in the tree is what makes "this authority has no recipients" representable at all.
 *
 * <p>Note for whoever builds the submission leg: a non-null {@code groupId} makes the consumer query
 * progression for group member cases and throw if it finds none, so a schema-valid body can still
 * fail server-side purely because the field was populated. The design rules already confine
 * {@code groupId} to group-master prosecution cases.
 *
 * @param registerDate               the date the register is batched under; required by the schema
 * @param hearingDate                the date of the hearing shared; required by the schema
 * @param hearingId                  the resulted hearing; required by the schema
 * @param prosecutionAuthorityId     the authority this register is for; required by the schema
 * @param prosecutionAuthorityCode   the authority's reference-data code; required by the schema
 * @param prosecutionAuthorityOuCode the authority's organisation unit code
 * @param majorCreditorCode          the major creditor code
 * @param prosecutionAuthorityName   the authority's name, for the rendered document
 * @param fileName                   the name the generated document is filed under; required
 * @param recipients                 the addressees of the generated document
 * @param hearingVenue               the venue and its sessions; required by the schema
 * @param groupId                    the group the master prosecution case belongs to
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InformantRegisterDocument(
        @JsonFormat(shape = JsonFormat.Shape.STRING) ZonedDateTime registerDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) ZonedDateTime hearingDate,
        UUID hearingId,
        UUID prosecutionAuthorityId,
        String prosecutionAuthorityCode,
        String prosecutionAuthorityOuCode,
        String majorCreditorCode,
        String prosecutionAuthorityName,
        String fileName,
        List<InformantRegisterRecipient> recipients,
        InformantRegisterHearingVenue hearingVenue,
        UUID groupId) {

    /**
     * Freezes the recipient list so the document cannot be changed after it is built.
     */
    public InformantRegisterDocument {
        recipients = ContractLists.frozen(recipients);
    }
}
