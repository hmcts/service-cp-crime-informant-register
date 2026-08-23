package uk.gov.hmcts.cp.informantregister.domain;

/**
 * Whether a user-group selection whitelists or blacklists the groups it names.
 *
 * <p>A port of {@code SetNowVariants/UserGroupType.js}, whose two members are the strings
 * {@code "include"} and {@code "exclude"}. They are an enum here rather than strings because the
 * legacy compares them by identity against those two literals and nothing else — there is no third
 * value and no wire form to parse.
 *
 * <p><strong>Unreachable from the informant-register flow.</strong>
 * {@code InformantRegisterSubscriptions} never sets a user group on the subscription object it
 * builds ({@code InformantRegisterSubscriptions/index.js:44-51}), so the branch this type selects is
 * dead for this service. It is ported because the shared matching kernel reads it and its Jest suite
 * exercises it, and a port of that kernel that quietly dropped the branch would be a port of
 * something else.
 */
public enum UserGroupType {

    /** The subscription must carry every one of the named groups. */
    INCLUDE,

    /** The subscription must carry none of the named groups. */
    EXCLUDE
}
