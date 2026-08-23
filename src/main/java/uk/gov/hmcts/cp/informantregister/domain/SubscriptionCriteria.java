package uk.gov.hmcts.cp.informantregister.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Everything the shared matching kernel is asked to decide a subscription against.
 *
 * <p>A port of {@code NowsHelper/service/SubscriptionObject.js}. The legacy class is a bag of
 * fields assembled by whichever flow is calling — the informant register fills four of them
 * ({@code InformantRegisterSubscriptions/index.js:44-51}) and leaves the rest {@code undefined}.
 *
 * <p><strong>The candidate subscriptions and the judicial results stay canonical trees.</strong>
 * Both are read, never produced: the subscriptions are reference data this service does not own and
 * hands on untouched to the recipient mapping, and the judicial results are part of the hearing
 * payload. Typing either would mean writing a model of somebody else's document
 * (constitution Principle IV).
 *
 * <p><strong>{@code isNotificationApi} is deliberately absent.</strong> The legacy class declares it
 * and initialises it to {@code undefined}, but no line of {@code SubscriptionsService.js} reads it,
 * so a component here would model a value that cannot change any answer.
 *
 * <p><strong>{@code nowId} and {@code userGroup} are always absent in this flow</strong> — the
 * informant register never sets them. They are carried because the kernel branches on them and its
 * own Jest suite drives those branches.
 *
 * @param nowId           the NOW being matched, when the calling flow has one
 * @param ouCode          the code the court-house and informant-code branches compare against; for
 *                        the informant register this is the authority's <em>major creditor</em>
 *                        code, not its organisation unit code
 * @param userGroup       the user groups the variant is restricted to, when the calling flow has one
 * @param vocabulary      the defendant vocabulary flags the subscription rules are applied to
 * @param subscriptions   the candidate subscriptions, as reference data returned them
 * @param judicialResults every judicial result gathered across the register's defendants
 */
public record SubscriptionCriteria(
        String nowId,
        String ouCode,
        UserGroupSelection userGroup,
        RegisterVocabulary vocabulary,
        List<JsonNode> subscriptions,
        List<JsonNode> judicialResults) {

    /**
     * Freezes the two lists, and reads an absent one as the empty list the legacy constructor
     * defaults to ({@code SubscriptionObject.js:7-8}).
     */
    public SubscriptionCriteria {
        subscriptions = subscriptions == null ? List.of() : FragmentLists.frozen(subscriptions);
        judicialResults = judicialResults == null ? List.of() : FragmentLists.frozen(judicialResults);
    }
}
