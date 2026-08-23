package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.RegisterResult;
import uk.gov.hmcts.cp.informantregister.domain.ResultLevel;

/**
 * The Java twin of the legacy mapper suite's own builder helper,
 * {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/test/ModelObjects.js}.
 *
 * <p>Six of the mapper Jest files build their inputs from these classes rather than from fixtures, so
 * twinning those cases means twinning the builder first. Every factory below corresponds to one JS
 * class and reproduces <strong>what that constructor leaves undefined as well as what it sets</strong>
 * — the difference decides the answer in several cases. Two examples that bite:
 *
 * <ul>
 *   <li>{@code new Hearing()} sets {@code courtCentre} to {@code {roomName: ""}} and leaves
 *       {@code isBoxHearing}, {@code hearingDays} and {@code prosecutionCases} undefined. An empty
 *       string room name is not the same as an absent one, and an absent {@code hearingDays} is what
 *       makes {@code CourtSessionMapper} throw.</li>
 *   <li>{@code new Defendant()} sets {@code personDefendant} to an object whose name fields sit at the
 *       <em>top level</em>, with no {@code personDetails} beneath — which is why every
 *       {@code DefendantMapper} case overwrites it, and why the cases that do not never read a name.
 *       The shape is reproduced as written rather than corrected.</li>
 * </ul>
 *
 * <p>The hearing side is built as {@link JsonNode} because that is how the port reads a hearing
 * (constitution Principle IV, canonical JSON in). The register side — the fragment and its defendants
 * — is built as the typed records the previous step produces. Where a JS constructor leaves a field
 * undefined, the JSON factory omits the key; a {@code null} argument means "the Jest case did not
 * pass one", which is not the same as passing an empty string, and several cases turn on exactly
 * that.
 */
final class ModelObjects {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ModelObjects() {
    }

    /**
     * The JS {@code Hearing}: a court centre with an empty room name, and nothing else set.
     *
     * @return the hearing tree
     */
    static ObjectNode hearing() {
        final ObjectNode hearing = NODES.objectNode();
        hearing.set("courtCentre", NODES.objectNode().put("roomName", ""));
        return hearing;
    }

    /**
     * The JS {@code HearingDay}.
     *
     * @param sittingDay the sitting day
     * @param startTime  the start time
     * @return the hearing-day tree
     */
    static ObjectNode hearingDay(final String sittingDay, final String startTime) {
        return NODES.objectNode().put("sittingDay", sittingDay).put("startTime", startTime);
    }

    /**
     * The JS {@code ProsecutionCase}, whose identifier carries only the arguments it was given.
     *
     * @param prosecutionAuthorityId the prosecuting authority
     * @param caseUrn                the case URN; {@code null} to leave it undefined
     * @param authorityReference     the authority's own reference; {@code null} to leave it undefined
     * @return the prosecution-case tree
     */
    static ObjectNode prosecutionCase(
            final String prosecutionAuthorityId,
            final String caseUrn,
            final String authorityReference) {

        final ObjectNode identifier = NODES.objectNode();
        put(identifier, "prosecutionAuthorityId", prosecutionAuthorityId);
        put(identifier, "caseURN", caseUrn);
        put(identifier, "prosecutionAuthorityReference", authorityReference);
        final ObjectNode prosecutionCase = NODES.objectNode();
        prosecutionCase.set("prosecutionCaseIdentifier", identifier);
        return prosecutionCase;
    }

    /**
     * The JS {@code Defendant}, including its {@code personDetails}-less {@code personDefendant}.
     *
     * @param offences the defendant's offences; {@code null} to leave the field undefined
     * @return the defendant tree
     */
    static ObjectNode defendant(final ArrayNode offences) {
        final ObjectNode defendant = NODES.objectNode();
        defendant.set("personDefendant", blankPersonDefendant());
        if (offences != null) {
            defendant.set("offences", offences);
        }
        return defendant;
    }

    /**
     * The JS {@code MasterDefendant} — the {@code Defendant} shape without offences.
     *
     * @return the master-defendant tree
     */
    static ObjectNode masterDefendant() {
        final ObjectNode masterDefendant = NODES.objectNode();
        masterDefendant.set("personDefendant", blankPersonDefendant());
        return masterDefendant;
    }

    /**
     * The {@code personDefendant} both defendant builders start from: name fields at the top level,
     * no {@code personDetails}, and an empty address.
     *
     * @return the person-defendant tree
     */
    private static ObjectNode blankPersonDefendant() {
        final ObjectNode person = NODES.objectNode();
        person.put("firstName", "");
        person.put("middleName", "");
        person.put("lastName", "");
        person.put("dateOfBirth", "");
        person.put("nationalityCode", "");
        person.put("arrestSummonsNumber", "");
        person.set("address", NODES.objectNode());
        return person;
    }

    /**
     * The JS {@code Offence}, which starts with an empty plea and an empty verdict.
     *
     * @param offenceCode  the offence code
     * @param orderIndex   the order index
     * @param offenceTitle the offence title
     * @return the offence tree
     */
    static ObjectNode offence(
            final String offenceCode, final int orderIndex, final String offenceTitle) {
        final ObjectNode offence = NODES.objectNode();
        offence.put("offenceCode", offenceCode);
        offence.put("orderIndex", orderIndex);
        offence.put("offenceTitle", offenceTitle);
        offence.set("plea", NODES.objectNode());
        offence.set("verdict", NODES.objectNode());
        return offence;
    }

    /**
     * The JS {@code CourtApplication}.
     *
     * @param subject   the subject
     * @param applicant the applicant; {@code null} to leave it undefined
     * @return the court-application tree
     */
    static ObjectNode courtApplication(final ObjectNode subject, final ObjectNode applicant) {
        final ObjectNode application = NODES.objectNode();
        application.set("subject", subject);
        if (applicant != null) {
            application.set("applicant", applicant);
        }
        return application;
    }

    /**
     * The JS {@code Subject}.
     *
     * @param masterDefendant the master defendant; {@code null} to leave it undefined
     * @return the subject tree
     */
    static ObjectNode subject(final ObjectNode masterDefendant) {
        final ObjectNode subject = NODES.objectNode();
        if (masterDefendant != null) {
            subject.set("masterDefendant", masterDefendant);
        }
        return subject;
    }

    /**
     * The JS {@code Applicant}.
     *
     * @param prosecutingAuthority the prosecuting authority
     * @return the applicant tree
     */
    static ObjectNode applicant(final ObjectNode prosecutingAuthority) {
        return NODES.objectNode().<ObjectNode>set("prosecutingAuthority", prosecutingAuthority);
    }

    /**
     * The JS {@code ProsecutingAuthority}.
     *
     * @param prosecutionAuthorityId   the authority id
     * @param prosecutionAuthorityCode the authority code; {@code null} to leave it undefined
     * @return the prosecuting-authority tree
     */
    static ObjectNode prosecutingAuthority(
            final String prosecutionAuthorityId, final String prosecutionAuthorityCode) {
        final ObjectNode authority = NODES.objectNode();
        put(authority, "prosecutionAuthorityId", prosecutionAuthorityId);
        put(authority, "prosecutionAuthorityCode", prosecutionAuthorityCode);
        return authority;
    }

    /**
     * The JS {@code CourtOrder}, which wraps its single argument in an array.
     *
     * @param courtOrderOffence the one court-order offence
     * @return the court-order tree
     */
    static ObjectNode courtOrder(final ObjectNode courtOrderOffence) {
        return NODES.objectNode()
                .<ObjectNode>set("courtOrderOffences", NODES.arrayNode().add(courtOrderOffence));
    }

    /**
     * The JS {@code CourtOrderOffences}.
     *
     * @param offence            the offence
     * @param caseUrn            the case URN; {@code null} to leave it undefined
     * @param authorityReference the authority reference; {@code null} to leave it undefined
     * @return the court-order-offence tree
     */
    static ObjectNode courtOrderOffence(
            final ObjectNode offence, final String caseUrn, final String authorityReference) {
        final ObjectNode courtOrderOffence = NODES.objectNode();
        courtOrderOffence.set("offence", offence);
        courtOrderOffence.set("prosecutionCaseIdentifier", identifier(caseUrn, authorityReference));
        return courtOrderOffence;
    }

    /**
     * The JS {@code CourtApplicationCases}.
     *
     * @param offences           the offences; {@code null} to leave the field undefined
     * @param caseUrn            the case URN; {@code null} to leave it undefined
     * @param authorityReference the authority reference; {@code null} to leave it undefined
     * @return the court-application-case tree
     */
    static ObjectNode courtApplicationCase(
            final ArrayNode offences, final String caseUrn, final String authorityReference) {
        final ObjectNode applicationCase = NODES.objectNode();
        if (offences != null) {
            applicationCase.set("offences", offences);
        }
        applicationCase.set("prosecutionCaseIdentifier", identifier(caseUrn, authorityReference));
        return applicationCase;
    }

    /**
     * The two-field {@code prosecutionCaseIdentifier} both application shapes carry.
     *
     * @param caseUrn            the case URN; {@code null} to leave it undefined
     * @param authorityReference the authority reference; {@code null} to leave it undefined
     * @return the identifier tree
     */
    private static ObjectNode identifier(final String caseUrn, final String authorityReference) {
        final ObjectNode identifier = NODES.objectNode();
        put(identifier, "caseURN", caseUrn);
        put(identifier, "prosecutionAuthorityReference", authorityReference);
        return identifier;
    }

    /**
     * The JS {@code Result} — a judicial result with only a code and its text.
     *
     * @param cjsCode    the CJS result code
     * @param resultText the result text
     * @return the judicial-result tree
     */
    static ObjectNode judicialResult(final String cjsCode, final String resultText) {
        return NODES.objectNode().put("cjsCode", cjsCode).put("resultText", resultText);
    }

    /**
     * An array node holding the given members, for the fields that take one.
     *
     * @param members the members
     * @return the array node
     */
    static ArrayNode array(final JsonNode... members) {
        final ArrayNode array = NODES.arrayNode();
        for (final JsonNode member : members) {
            array.add(member);
        }
        return array;
    }

    /**
     * The JS {@code DefendantContextBase}: an identity and an empty result list.
     *
     * <p>The typed equivalent is {@link RegisterDefendant}, whose other components the JS constructor
     * leaves undefined.
     *
     * @param masterDefendantId the identity
     * @param results           the results this defendant carries
     * @return the register defendant
     */
    static RegisterDefendant defendantContextBase(
            final String masterDefendantId, final RegisterResult... results) {
        return new RegisterDefendant(
                null, List.of(results), null, null, masterDefendantId, null, null, null);
    }

    /**
     * A register defendant carrying the cases and applications the Jest case gives it.
     *
     * @param masterDefendantId the identity
     * @param cases             the prosecution cases; {@code null} to leave the field undefined
     * @param applications      the court applications; {@code null} to leave the field undefined
     * @param results           the results this defendant carries
     * @return the register defendant
     */
    static RegisterDefendant registerDefendant(
            final String masterDefendantId,
            final List<String> cases,
            final List<String> applications,
            final List<RegisterResult> results) {
        return new RegisterDefendant(
                null, results, cases, applications, masterDefendantId, null, null, null);
    }

    /**
     * One tagged result, as the {@code DefendantContextBase.results} entries the Jest cases declare
     * inline.
     *
     * @param level             the level the result was recorded at
     * @param judicialResult    the judicial result itself
     * @param prosecutionCaseId the case, for case-level results
     * @param offenceId         the offence, for offence-level results
     * @param applicationId     the application, for application-level results
     * @return the tagged result
     */
    static RegisterResult taggedResult(
            final ResultLevel level,
            final JsonNode judicialResult,
            final String prosecutionCaseId,
            final String offenceId,
            final String applicationId) {
        return new RegisterResult(
                prosecutionCaseId, null, offenceId, applicationId, level, null,
                judicialResult, null, null);
    }

    /**
     * The JS {@code InformantRegisterSubscription} — the fragment the mapper suite passes around,
     * which carries an authority and a defendant list and nothing else.
     *
     * @param prosecutionAuthorityId the authority
     * @param registerDefendants     the defendants
     * @return the register fragment
     */
    static RegisterFragment fragment(
            final String prosecutionAuthorityId, final RegisterDefendant... registerDefendants) {
        return new RegisterFragment(
                null, null, null, prosecutionAuthorityId, null, null, null, null,
                new ArrayList<>(List.of(registerDefendants)), null);
    }

    /**
     * Sets a field only when the Jest case supplied one.
     *
     * @param target the object to set on
     * @param field  the field name
     * @param value  the value; {@code null} leaves the field undefined
     */
    private static void put(final ObjectNode target, final String field, final String value) {
        if (value != null) {
            target.put(field, value);
        }
    }
}
