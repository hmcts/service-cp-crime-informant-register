package uk.gov.hmcts.cp.informantregister.pipeline;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterRecipient;

/**
 * Who receives one authority's register, derived from the subscriptions matched to it.
 *
 * <p>A port of {@code OutboundInformantRegister/InformantRegisterAggregationRequest/Mapper/
 * RecipientMapper.js}. The matched subscriptions are reference data, so they stay canonical trees
 * here exactly as the hearing does; the recipients they produce are typed, because they are what this
 * service sends.
 *
 * <p><strong>Email only, and letter delivery is ignored.</strong> A subscription produces a recipient
 * only when it is for distribution, is for email delivery, and names a recipient. Letter delivery is
 * handled by a log line and nothing else — that is defect D17, a sanctioned oddity, and the register
 * does not support the channel. Note the shape of the legacy code: the two letter-delivery checks sit
 * <em>outside</em> the email branch, so a subscription that is both is still emitted as an email
 * recipient. Treating letter delivery as "not our channel, skip the subscription" would silently drop
 * someone who should be receiving email.
 *
 * <p><strong>A recipient with no email address is dropped, silently.</strong> The test is
 * {@code emailAddress1 !== undefined}, which is narrower than it looks: an <em>absent</em> address
 * drops the recipient, while an address present as null or as an empty string produces a recipient
 * with a blank one. The design register describes this as "dropped when the address is missing"; the
 * code disagrees with the register, and the code is what is ported. Raising an error, substituting
 * the second address, or dropping the blank ones would each change who receives a register.
 *
 * <p><strong>No recipients at all means no component.</strong> The legacy returns {@code undefined}
 * rather than an empty array, and the contract gives {@code recipients} {@code minItems: 1} — so an
 * absent component is the valid shape for "nobody is subscribed" and an empty array is not.
 */
final class RecipientMapper {

    /** The template a subscription that names none is sent with. */
    private static final String DEFAULT_TEMPLATE = "ir_standard";

    private final List<JsonNode> matchedSubscriptions;

    /**
     * Creates the mapper.
     *
     * @param matchedSubscriptions the subscriptions matched to this authority's fragment
     */
    /* default */ RecipientMapper(final List<JsonNode> matchedSubscriptions) {
        this.matchedSubscriptions = matchedSubscriptions;
    }

    /**
     * Builds the recipients.
     *
     * @return the recipients, or {@code null} when none survived
     */
    /* default */ List<InformantRegisterRecipient> build() {
        final List<InformantRegisterRecipient> recipients = new ArrayList<>();
        for (final JsonNode member : matchedSubscriptions) {
            // `subscription.forDistribution` (RecipientMapper.js:15) — the member is dereferenced
            // with no guard, so a null in the matched-subscription array kills the hearing there.
            final JsonNode subscription = Json.dereferencedElement(member, "matchedSubscriptions");
            if (!Json.truthy(subscription, "forDistribution")
                    || !Json.truthy(subscription, "emailDelivery")
                    || !Json.truthy(subscription, "recipient")) {
                continue;
            }
            final JsonNode recipient = Json.at(subscription, "recipient");
            // Absent drops the recipient; present-but-null or empty does not. See the class
            // documentation — the narrowness is the behaviour, not an oversight in this port.
            if (Json.at(recipient, "emailAddress1") == null) {
                continue;
            }
            recipients.add(new InformantRegisterRecipient(
                    Json.text(recipient, "organisationName"),
                    trimmed(Json.text(recipient, "emailAddress1")),
                    Json.truthy(recipient, "emailAddress2")
                            ? trimmed(Json.text(recipient, "emailAddress2")) : null,
                    Json.truthy(subscription, "emailTemplateName")
                            ? Json.text(subscription, "emailTemplateName") : DEFAULT_TEMPLATE));
        }
        return recipients.isEmpty() ? null : recipients;
    }

    /**
     * An email address with its surrounding whitespace removed.
     *
     * <p>Ports {@code trimEmailAddress}, whose guard is truthiness: a null or empty address is
     * returned as it arrived rather than trimmed, which for those two values is the same answer.
     * The trim itself is {@link JsStrings#trim}, not {@link String#trim()} — the two disagree about
     * the non-breaking space, and an address is not a value to be approximate about.
     *
     * @param emailAddress the address; may be {@code null}
     * @return the trimmed address, or the value unchanged when there is nothing to trim
     */
    private static String trimmed(final String emailAddress) {
        return JsStrings.trim(emailAddress);
    }
}
