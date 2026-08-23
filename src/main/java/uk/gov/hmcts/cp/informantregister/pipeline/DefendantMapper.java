package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterDefendant;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The defendants of one court session, with their personal details, cases and results.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * DefendantMapper.js}. The fragment says which identities belong to this authority; this mapper finds
 * each identity's records in the hearing and turns the first one into the register's entry.
 *
 * <p><strong>First defendant wins.</strong> An identity can appear as several defendant records —
 * once per case, and again as an application's subject. The legacy takes {@code defendants[0]} and
 * discards the rest, so a fuller record behind a sparser one contributes nothing but its arrest
 * summons number, which is read per case elsewhere. Ported as written.
 *
 * <p><strong>An identity the hearing does not carry produces no entry at all</strong> — not an empty
 * one. That is the {@code if (defendants.length)} guard, and it is what keeps a fragment whose
 * defendants have all been filtered away from putting empty rows on a register.
 *
 * <p><strong>Person or organisation, and person first.</strong> Every component below has two
 * sources, and the person record is consulted first wherever both could answer. The organisation
 * branch is reproduced from the source rather than from a test: no test in the legacy repository
 * executes any of it (parity-pack BS-06).
 *
 * <p>Everything this mapper reads is defendant PII. Nothing here is logged.
 */
final class DefendantMapper {

    private final JsonNode hearing;
    private final RegisterFragment fragment;
    private final ResultDataMapper resultDataMapper;

    /**
     * Creates the mapper for one authority's fragment.
     *
     * @param hearing          the canonical hearing tree
     * @param fragment         the authority's fragment
     * @param resultDataMapper the mapper for the detail hanging off each result
     */
    DefendantMapper(
            final JsonNode hearing,
            final RegisterFragment fragment,
            final ResultDataMapper resultDataMapper) {
        this.hearing = hearing;
        this.fragment = fragment;
        this.resultDataMapper = resultDataMapper;
    }

    /**
     * Maps every one of the fragment's defendants the hearing still carries a record for.
     *
     * @return the defendants, in fragment order; never {@code null}
     */
    List<InformantRegisterDefendant> build() {
        final List<InformantRegisterDefendant> mapped = new ArrayList<>();
        // `informantRegister.registerDefendants.forEach` (DefendantMapper.js:19) — dereferenced with
        // no guard. The fragment builder always supplies a list, so this is unreachable from the
        // pipeline; it is refused rather than read as "no defendants" for the reason in Json.
        if (fragment.registerDefendants() == null) {
            throw new TransformationFailedException("register fragment carries no defendant list");
        }
        for (final RegisterDefendant registerDefendant : fragment.registerDefendants()) {
            final List<JsonNode> records = defendantsOf(registerDefendant.masterDefendantId());
            if (records.isEmpty()) {
                continue;
            }
            mapped.add(map(records.getFirst(), registerDefendant));
        }
        return mapped;
    }

    /**
     * Every defendant record in the hearing carrying one identity, cases first and applications
     * second.
     *
     * <p>Ports {@code getDefendants}. The same identity reached twice yields two records, and the
     * caller keeps only the first.
     *
     * @param masterDefendantId the identity to gather for
     * @return the records; never {@code null}
     */
    List<JsonNode> defendantsOf(final String masterDefendantId) {
        final List<JsonNode> records = new ArrayList<>();

        for (final JsonNode prosecutionCase : Json.array(hearing, "prosecutionCases")) {
            // `_(prosecutionCases).flatMap('defendants')` then `.filter(d => d.masterDefendantId…)`
            // (DefendantMapper.js:176-178) — a case with no defendants flattens to a single
            // undefined and the filter reads a property off it, so the legacy throws.
            for (final JsonNode record : Json.dereferencedArray(prosecutionCase, "defendants")) {
                if (Objects.equals(Json.text(record, "masterDefendantId"), masterDefendantId)) {
                    records.add(record);
                }
            }
        }

        for (final JsonNode application : Json.array(hearing, "courtApplications")) {
            // `courtApplication.subject.masterDefendant` (DefendantMapper.js:180) — `subject` is
            // dereferenced with no guard, while `masterDefendant` is tested before it is read.
            final JsonNode masterDefendant =
                    Json.at(Json.dereferenced(application, "subject"), "masterDefendant");
            if (Json.truthy(masterDefendant) && Objects.equals(
                    Json.text(masterDefendant, "masterDefendantId"), masterDefendantId)) {
                records.add(masterDefendant);
            }
        }

        return records;
    }

    /**
     * Maps one defendant record onto its register entry.
     *
     * @param record            the defendant record from the hearing
     * @param registerDefendant the fragment's defendant, carrying the results and the case list
     * @return the outbound defendant
     */
    private InformantRegisterDefendant map(
            final JsonNode record, final RegisterDefendant registerDefendant) {

        final ResultMapper resultMapper = new ResultMapper(registerDefendant, resultDataMapper);
        final JsonNode person = Json.at(record, "personDefendant");
        // Not resolved here. Every legacy branch that reaches the organisation is an `else if` or
        // the second half of an `||`, so `legalEntityDefendant.organisation` is only dereferenced
        // when the person record did not answer — and a defendant carrying both a populated
        // `personDefendant` and an empty `legalEntityDefendant` is mapped by the legacy without
        // complaint. Resolving eagerly would refuse that hearing outright.
        final Supplier<JsonNode> organisation = () -> organisationOf(record);

        return new InformantRegisterDefendant(
                name(person, organisation),
                // `defendant.personDefendant.personDetails.dateOfBirth` (DefendantMapper.js:109) —
                // `personDetails` is dereferenced with no guard on every one of these reads.
                Json.truthy(person) ? Json.text(details(person), "dateOfBirth") : null,
                addressLine(person, organisation, "address1", false),
                addressLine(person, organisation, "address2", true),
                addressLine(person, organisation, "address3", true),
                addressLine(person, organisation, "address4", true),
                addressLine(person, organisation, "address5", true),
                addressLine(person, organisation, "postcode", true),
                personComponent(person, "nationalityCode"),
                personComponent(person, "title"),
                personComponent(person, "firstName"),
                lastName(person, organisation),
                new CaseOrApplicationMapper(hearing, fragment, registerDefendant, resultMapper)
                        .build(),
                resultMapper.defendantLevel());
    }

    /**
     * The defendant's name: the person's full name, or the organisation's.
     *
     * @param person       the person record, if any
     * @param organisation the organisation record, resolved only if the person record is absent
     * @return the name, or {@code null}
     */
    private static String name(final JsonNode person, final Supplier<JsonNode> organisation) {
        if (Json.truthy(person)) {
            return fullName(person);
        }
        final JsonNode resolved = organisation.get();
        return Json.truthy(resolved) ? Json.text(resolved, "name") : null;
    }

    /**
     * The person's name, as the truthy parts joined by single spaces.
     *
     * <p>{@code [first, middle, last].filter(item => item).join(' ').trim()} — the filter is what
     * keeps a missing middle name from leaving a double space, and this one <em>is</em> guarded on
     * {@code personDetails}, unlike its neighbours.
     *
     * @param person the person record
     * @return the full name, or {@code null} when the record has no details
     */
    private static String fullName(final JsonNode person) {
        final JsonNode details = Json.at(person, "personDetails");
        if (!Json.truthy(details)) {
            return null;
        }
        final StringBuilder name = new StringBuilder();
        for (final String part : List.of("firstName", "middleName", "lastName")) {
            if (Json.truthy(details, part)) {
                if (!name.isEmpty()) {
                    name.append(' ');
                }
                name.append(Json.text(details, part));
            }
        }
        // `JsStrings.trim`, not `String.trim`: the legacy trims what ECMAScript calls whitespace,
        // which includes the non-breaking space a pasted name can carry.
        return JsStrings.trim(name.toString());
    }

    /**
     * The defendant's last name: the person's, or the organisation's own name.
     *
     * @param person       the person record, if any
     * @param organisation the organisation record, resolved only if the person record is absent
     * @return the last name, or {@code null}
     */
    private static String lastName(final JsonNode person, final Supplier<JsonNode> organisation) {
        if (Json.truthy(person)) {
            return Json.text(details(person), "lastName");
        }
        final JsonNode resolved = organisation.get();
        return Json.truthy(resolved) ? Json.text(resolved, "name") : null;
    }

    /**
     * A person-only component, present only when the person record carries a truthy value for it.
     *
     * @param person the person record, if any
     * @param field  the field to read
     * @return the value, or {@code null}
     */
    private static String personComponent(final JsonNode person, final String field) {
        if (!Json.truthy(person)) {
            return null;
        }
        final JsonNode details = details(person);
        return Json.truthy(details, field) ? Json.text(details, field) : null;
    }

    /**
     * One address component, from the person's address or the organisation's.
     *
     * <p>{@code address1} is the odd one out and the flag says so: the legacy reads it as soon as an
     * address exists, while every other line and the postcode must also be truthy in their own right
     * before that source is used. So a person whose address has only a second line yields
     * {@code address1} absent — and does <em>not</em> fall through to the organisation's second line,
     * because the person's address answered first.
     *
     * <p>The organisation is resolved only once the person's address has failed to answer, because
     * that is where the legacy's {@code else if} evaluates
     * {@code isLegalEntityDefendantAddressAvailable} and so where it dereferences
     * {@code legalEntityDefendant.organisation}.
     *
     * @param person          the person record, if any
     * @param organisation    the organisation record, resolved only if the person's does not answer
     * @param field           the address field to read
     * @param mustBeNonEmpty  whether the value must be truthy for its source to be used
     * @return the value, or {@code null}
     */
    private static String addressLine(
            final JsonNode person,
            final Supplier<JsonNode> organisation,
            final String field,
            final boolean mustBeNonEmpty) {

        final JsonNode personAddress =
                Json.truthy(person) ? Json.at(details(person), "address") : null;
        if (Json.truthy(personAddress) && (!mustBeNonEmpty || Json.truthy(personAddress, field))) {
            return Json.text(personAddress, field);
        }
        final JsonNode resolved = organisation.get();
        final JsonNode organisationAddress =
                Json.truthy(resolved) ? Json.at(resolved, "address") : null;
        if (Json.truthy(organisationAddress)
                && (!mustBeNonEmpty || Json.truthy(organisationAddress, field))) {
            return Json.text(organisationAddress, field);
        }
        return null;
    }

    /**
     * The person record's details, dereferenced the way the legacy dereferences them.
     *
     * @param person the person record
     * @return the details
     */
    private static JsonNode details(final JsonNode person) {
        return Json.dereferenced(person, "personDetails");
    }

    /**
     * The organisation of a legal-entity defendant, if it has one.
     *
     * <p>{@code legalEntityDefendant.organisation.address} is dereferenced with no guard on
     * {@code organisation} (`DefendantMapper.js:133`), so a legal entity without one is a refusal.
     * Called lazily, never up front — see {@link #map} for why that difference is observable.
     *
     * @param record the defendant record
     * @return the organisation, or {@code null} when the record is not a legal entity
     */
    private static JsonNode organisationOf(final JsonNode record) {
        final JsonNode legalEntity = Json.at(record, "legalEntityDefendant");
        return Json.truthy(legalEntity) ? Json.dereferenced(legalEntity, "organisation") : null;
    }
}
