package uk.gov.hmcts.cp.informantregister.domain;

import java.util.List;

/**
 * The user groups a variant is restricted to, and whether they are a whitelist or a blacklist.
 *
 * <p>A port of {@code SetNowVariants/UserGroup.js}. As with {@link UserGroupType}, nothing in the
 * informant-register flow ever supplies one — the subscription object this service builds leaves it
 * absent — but the shared matching kernel reads it, so the port carries it.
 *
 * @param type       whether {@code userGroups} is a whitelist or a blacklist
 * @param userGroups the groups named
 */
public record UserGroupSelection(UserGroupType type, List<String> userGroups) {

    /**
     * Freezes the group list so the selection cannot be changed after it is built.
     */
    public UserGroupSelection {
        userGroups = FragmentLists.frozen(userGroups);
    }
}
