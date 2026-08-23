package uk.gov.hmcts.cp.informantregister.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Freezes the list-valued components of the intermediate register-fragment tree.
 *
 * <p>This exists alongside {@link ContractLists} rather than reusing it, because the two trees have
 * opposite tolerances and one helper cannot honestly serve both.
 * {@link ContractLists#frozen(List)} is built on {@link List#copyOf}, which <em>rejects null
 * elements</em> — correct for the outbound document, where a null would violate a closed schema.
 *
 * <p>The fragment tree is the opposite case. It is a port of a JavaScript structure that pushes
 * {@code undefined} into its arrays whenever the hearing payload omits an identifier — a prosecution
 * case with no {@code id} contributes {@code undefined} to {@code cases}, and
 * {@code JSON.stringify} writes that element as {@code null}. The parity goldens contain exactly
 * that, so dropping or rejecting those elements would change the observable shape. This helper
 * therefore preserves null elements while still returning an unmodifiable copy.
 */
final class FragmentLists {

    private FragmentLists() {
    }

    /**
     * Returns an unmodifiable copy of the given list, preserving any null elements.
     *
     * @param values the list a caller supplied, possibly {@code null}
     * @param <T>    the element type
     * @return an unmodifiable copy, or {@code null} if the list itself was absent
     */
    /* default */ static <T> List<T> frozen(final List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
