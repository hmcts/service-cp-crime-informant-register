package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which ordered date wins, and — the part that decides whether a hearing survives — which ones are
 * compared at all.
 *
 * <p>Both legacy call sites end in {@code Array.prototype.sort} with a comparator that parses, and
 * that parse throws. So whether a bad ordered date destroys the hearing turns entirely on whether
 * {@code sort} ever hands it to the comparator, and {@code sort} has two rules that decide it: an
 * array of fewer than two elements is never compared at all, and an {@code undefined} element is
 * moved to the end without being compared either. A port that compares everything refuses hearings
 * the legacy files perfectly happily.
 */
@DisplayName("The ordered-date sort, with Array.prototype.sort's own rules")
class OrderedDatesTest {

    private final HearingDates dates = new HearingDates(
            Clock.fixed(Instant.parse("2026-08-21T09:15:00Z"), ZoneOffset.UTC));

    private static List<JsonNode> nodes(final String... values) {
        return Arrays.stream(values)
                .map(value -> value == null ? null : (JsonNode) JsonNodeFactory.instance.textNode(value))
                .toList();
    }

    @Test
    @DisplayName("answers the latest of several readable dates")
    void answers_the_latest_of_several() {
        assertThat(OrderedDates.latest(nodes("2020-01-20", "2021-03-11", "2019-12-31"), dates))
                .isEqualTo("2021-03-11");
    }

    @Test
    @DisplayName("keeps the first of two equal dates, as a stable sort does")
    void keeps_the_first_of_two_equal_dates() {
        // The comparator only reads the leading calendar day, so these two are equal to it and
        // JavaScript's stable sort leaves the earlier one first.
        assertThat(OrderedDates.latest(nodes("2020-01-20T09:00:00Z", "2020-01-20T17:00:00Z"), dates))
                .isEqualTo("2020-01-20T09:00:00Z");
    }

    @Test
    @DisplayName("never compares a single date, so an unreadable one survives alone")
    void never_compares_a_single_date() {
        // `['20-01-2020'].sort(cmp)` never calls cmp — and the two OutboundInformantRegister
        // fixtures depend on it: their one ordered date reaches the register unparsed and the
        // hearing is filed.
        assertThat(OrderedDates.latest(nodes("nonsense"), dates)).isEqualTo("nonsense");
    }

    @Test
    @DisplayName("moves an absent date to the end without comparing it")
    void moves_an_absent_date_to_the_end() {
        // `Array.prototype.sort` special-cases `undefined`: it is sorted last and the comparator is
        // never called with it. A result carrying no `orderedDate` therefore cannot destroy the
        // hearing, however many other results there are.
        assertThat(OrderedDates.latest(nodes("2020-01-20", null, "2019-01-01"), dates))
                .isEqualTo("2020-01-20");
        assertThat(OrderedDates.latest(nodes(null, "nonsense"), dates)).isEqualTo("nonsense");
    }

    @Test
    @DisplayName("answers nothing when every date is absent, as `[undefined][0]` does")
    void answers_nothing_when_every_date_is_absent() {
        assertThat(OrderedDates.latest(nodes(null, null), dates)).isNull();
        assertThat(OrderedDates.latest(List.of(), dates)).isNull();
    }

    @Test
    @DisplayName("compares an explicit null, because sort only skips `undefined`")
    void compares_an_explicit_null() {
        // A field present as JSON null is JavaScript `null`, not `undefined`, so sort compares it
        // and `moment(null, 'YYYY/MM/DD')` is invalid — which is the throw that loses the hearing.
        assertThatThrownBy(() -> OrderedDates.latest(
                List.of(JsonNodeFactory.instance.textNode("2020-01-20"),
                        JsonNodeFactory.instance.nullNode()), dates))
                .isInstanceOf(TransformationFailedException.class);
    }

    @Test
    @DisplayName("refuses an unreadable date once there is a second one to compare it with")
    void refuses_an_unreadable_date_once_there_is_a_second() {
        // This is defect D10 and pin s06: the same bad date is harmless alone and fatal in company,
        // and which one a hearing gets depends only on how many results a defendant happens to have.
        assertThatThrownBy(() -> OrderedDates.latest(nodes("2020-01-20", "not/a/date"), dates))
                .isInstanceOf(TransformationFailedException.class);
    }
}
