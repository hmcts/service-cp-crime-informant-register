package uk.gov.hmcts.cp.informantregister.domain;

import java.util.List;

/**
 * Freezes the list-valued components of the outbound document tree.
 *
 * <p>A record whose component is a {@code List} is only shallowly immutable: the caller keeps a
 * reference to the list it passed in and can still add to it afterwards. Every list in this tree is
 * therefore copied on the way in, so "records are immutable by design" means what it says rather
 * than what it looks like.
 *
 * <p>The copy is null-tolerant because absence is meaningful here. Every array in the outbound
 * contract is optional, and {@code @JsonInclude(NON_NULL)} drops a null component from the wire
 * altogether — which is the only correct rendering of "this authority has no recipients", since
 * every array in the schema also carries {@code minItems: 1} and so cannot legally be written as
 * {@code []}.
 */
final class ContractLists {

    private ContractLists() {
    }

    /**
     * Returns an unmodifiable copy of the given list, or {@code null} if it was absent.
     *
     * @param values the list a caller supplied, possibly {@code null}
     * @param <T>    the element type
     * @return an unmodifiable copy, or {@code null}
     */
    // Null is the contract, not an oversight: see the class documentation. Every array in the
    // outbound schema is optional and carries minItems: 1, so an absent list must be dropped from
    // the wire by @JsonInclude(NON_NULL). Returning empty, as the rule asks, would serialise [] and
    // violate a schema this service does not own.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    /* default */ static <T> List<T> frozen(final List<T> values) {
        return values == null ? null : List.copyOf(values);
    }
}
