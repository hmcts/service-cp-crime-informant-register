package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * One prosecuting authority's register fragment, turned into the command body that is sent for it.
 *
 * <p>A port of {@code OutboundInformantRegister/index.js} and the eight-mapper tree beneath it — the
 * last of the three transformation steps. It is pure: a hearing tree, a fragment and its matched
 * subscriptions in, one typed document out, no I/O and no clock beyond the injected one
 * (constitution Principle V).
 *
 * <p><strong>One document per call, not per hearing.</strong> The legacy activity loops the fragments
 * itself. Here the loop belongs to the caller, because {@link RegisterFragment} deliberately does not
 * model {@code matchedSubscriptions} — those are written by the subscription-matching step that sits
 * between the two, and the fragment record says so — so a fragment and its subscriptions arrive as
 * two arguments rather than as one object. Nothing else about the shape changes.
 *
 * <p><strong>What the legacy calls the model and what it actually sends are not the same.</strong>
 * {@code InformantRegisterAggregation} declares ten components; the activity assigns twelve, adding
 * {@code majorCreditorCode} and {@code prosecutionAuthorityName}. Both are valid against the frozen
 * contract, so the payload is right and the model is simply incomplete. All twelve are carried here.
 *
 * <p><strong>The two timestamps and the three identifiers change type at this boundary, and only
 * here.</strong> The fragment carries them as the strings the legacy renders — London wall-clock time
 * with a literal {@code Z} — and the consumer's own binding types them as {@code ZonedDateTime} and
 * {@code UUID}. Re-rendering a parsed {@code ZonedDateTime} reproduces the string it was parsed from,
 * so the D9 labelling survives the round trip intact; what does not survive is a value that is not
 * of that shape at all, and that case is deviations-register entry 10.
 */
public final class AggregationMapper {

    /** What JavaScript prints when an absent value is concatenated into a string. */
    private static final String UNDEFINED = "undefined";

    private final HearingDates dates;

    /**
     * Creates the mapper.
     *
     * @param dates the date service the file name's date comes from
     */
    public AggregationMapper(final HearingDates dates) {
        this.dates = dates;
    }

    /**
     * Builds the command body for one authority.
     *
     * @param hearing              the canonical hearing tree
     * @param fragment             the authority's fragment
     * @param matchedSubscriptions the subscriptions matched to it; empty when there are none
     * @return the command body
     */
    public InformantRegisterDocument build(
            final JsonNode hearing,
            final RegisterFragment fragment,
            final List<JsonNode> matchedSubscriptions) {

        final ResultDataMapper resultDataMapper = new ResultDataMapper(dates);
        return new InformantRegisterDocument(
                timestamp(fragment.registerDate(), "registerDate"),
                timestamp(fragment.hearingDate(), "hearingDate"),
                identifier(fragment.hearingId(), "hearingId"),
                identifier(fragment.prosecutionAuthorityId(), "prosecutionAuthorityId"),
                fragment.prosecutionAuthorityCode(),
                fragment.prosecutionAuthorityOuCode(),
                fragment.majorCreditorCode(),
                fragment.prosecutionAuthorityName(),
                fileName(fragment),
                new RecipientMapper(matchedSubscriptions).build(),
                new HearingVenueMapper(hearing, fragment, dates, resultDataMapper).build(),
                identifier(fragment.groupId(), "groupId"));
    }

    /**
     * The name the generated document is filed under.
     *
     * <p>{@code 'InformantRegister_' + code + '_' + getLocalDate(registerDate) + '.csv'}. Two details
     * are ported rather than improved. The date is the register date read <em>again</em> as a London
     * day, so around midnight the file name's date and the register date's own can disagree. And an
     * authority with no code does not produce a shorter name: JavaScript concatenates the absent
     * value as the six letters {@code undefined}, and so does this. Writing {@code null} there — the
     * natural Java answer — would be a different file name for the same hearing.
     *
     * @param fragment the authority's fragment
     * @return the file name
     */
    private String fileName(final RegisterFragment fragment) {
        final String code = fragment.prosecutionAuthorityCode();
        return "InformantRegister_"
                + (code == null ? UNDEFINED : code)
                + "_" + dates.localDate(fragment.registerDate()) + ".csv";
    }

    /**
     * One of the document's two timestamps.
     *
     * @param rendered the value the fragment carries; may be {@code null}
     * @param name     the component's name, for the failure message
     * @return the timestamp, or {@code null} when the fragment has none
     * @throws TransformationFailedException if the value is not a timestamp
     */
    private static ZonedDateTime timestamp(final String rendered, final String name) {
        if (rendered == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(rendered);
        } catch (DateTimeParseException notATimestamp) {
            // Deviations entry 10. The legacy would POST the value as it stands and let the
            // consumer's schema reject the command; the typed tree cannot hold it, so the delivery
            // is parked where support can see it instead. The value itself is never quoted — it is
            // the producer's text.
            throw new TransformationFailedException(
                    "register component '" + name + "' is not a timestamp");
        }
    }

    /**
     * One of the document's three identifiers.
     *
     * @param rendered the value the fragment carries; may be {@code null}
     * @param name     the component's name, for the failure message
     * @return the identifier, or {@code null} when the fragment has none
     * @throws TransformationFailedException if the value is not a UUID
     */
    private static UUID identifier(final String rendered, final String name) {
        if (rendered == null) {
            return null;
        }
        try {
            return UUID.fromString(rendered);
        } catch (IllegalArgumentException notAnIdentifier) {
            // Deviations entry 10, as above.
            throw new TransformationFailedException(
                    "register component '" + name + "' is not an identifier");
        }
    }
}
