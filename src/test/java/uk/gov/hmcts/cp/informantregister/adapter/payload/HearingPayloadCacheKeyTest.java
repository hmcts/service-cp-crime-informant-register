package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.informantregister.adapter.payload.HearingPayloadCacheKey.cacheKey;

/**
 * The cache key is somebody else's decision, so it is pinned character by character.
 *
 * <p>The producer writes the key and this service reads it. A key that is nearly right reads
 * nothing, and reading nothing is indistinguishable from a hearing that was never cached — which
 * would send every request to the query API and look, from here, like a cache that is simply cold.
 * That is exactly the sort of failure a running system does not report, so it is asserted against
 * the literal form the function app builds ({@code HearingResultedCacheQuery.getCacheKey}) rather
 * than against a second copy of the same string concatenation.
 */
@DisplayName("Hearing payload cache key")
class HearingPayloadCacheKeyTest {

    private static final String PREFIX = "INT_";
    private static final UUID HEARING_ID = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");
    private static final LocalDate HEARING_DAY = LocalDate.of(2026, 8, 21);

    @Nested
    @DisplayName("with a hearing day")
    class Dated {

        @Test
        void cacheKey_with_a_hearing_day_should_be_prefix_hearing_day_result() {
            assertThat(cacheKey(PREFIX, HEARING_ID, HEARING_DAY))
                    .isEqualTo("INT_1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa_2026-08-21_result_");
        }

        @Test
        void cacheKey_should_render_the_hearing_day_as_an_iso_date() {
            assertThat(cacheKey(PREFIX, HEARING_ID, LocalDate.of(2026, 1, 5)))
                    .contains("_2026-01-05_")
                    .doesNotContain("2026-1-5");
        }

        @Test
        void cacheKey_should_render_the_hearing_id_in_canonical_lower_case() {
            final UUID upperCased =
                    UUID.fromString("1C9D3F7A-88B1-4D5E-9C33-0F2A6B4E77AA");

            assertThat(cacheKey(PREFIX, upperCased, HEARING_DAY))
                    .isEqualTo(cacheKey(PREFIX, HEARING_ID, HEARING_DAY));
        }
    }

    @Nested
    @DisplayName("without a hearing day")
    class Undated {

        @Test
        void cacheKey_without_a_hearing_day_should_be_the_legacy_prefix_hearing_result_form() {
            assertThat(cacheKey(PREFIX, HEARING_ID, null))
                    .isEqualTo("INT_1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa_result_");
        }

        @Test
        void cacheKey_without_a_hearing_day_should_not_leave_a_separator_where_the_day_was() {
            assertThat(cacheKey(PREFIX, HEARING_ID, null)).doesNotContain("__");
        }
    }

    @Nested
    @DisplayName("prefix")
    class Prefix {

        /**
         * The prefix is configuration, not a constant baked in here. Only {@code INT_} is in scope
         * for this flow — SJP hearings stay in the NOWs function app ({@code doc/API_CONTRACTS.md})
         * — so the assertion uses a prefix that is nobody's, to prove the value is passed through
         * rather than to suggest another flow is supported.
         */
        @Test
        void cacheKey_should_use_the_supplied_prefix_verbatim() {
            assertThat(cacheKey("ZZZ_", HEARING_ID, HEARING_DAY)).startsWith("ZZZ_");
        }
    }
}
