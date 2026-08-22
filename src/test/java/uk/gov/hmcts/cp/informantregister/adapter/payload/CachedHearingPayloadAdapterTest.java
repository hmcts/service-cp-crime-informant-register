package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.informantregister.domain.FailureClassification;
import uk.gov.hmcts.cp.informantregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.informantregister.domain.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The order the two sources are consulted in, and what happens when neither answers.
 *
 * <p>Both are decisions the function app already made and this port has to keep. Cache first, query
 * side second ({@code HearingResultedCacheQuery.getHearing}); a cache that cannot be read is not a
 * request failure, because there the cache read is wrapped in a catch that returns {@code null} and
 * the caller carries straight on to the query API.
 *
 * <p>The last case is the one this port changes on purpose. Legacy returned {@code null} and the
 * orchestration reported success having produced nothing. Here the request is failed transiently, so
 * a redelivery gets another attempt and an exhausted request is dead-lettered where somebody can see
 * it — registered deviation 2 in {@code doc/DEVIATIONS.md}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Cached hearing payload adapter")
class CachedHearingPayloadAdapterTest {

    private static final String PREFIX = "INT_";
    private static final UUID HEARING_ID = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");
    private static final LocalDate HEARING_DAY = LocalDate.of(2026, 8, 21);
    private static final String DATED_KEY =
            "INT_1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa_2026-08-21_result_";
    private static final String LEGACY_KEY =
            "INT_1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa_result_";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private HearingPayloadCache cache;

    @Mock
    private HearingPayloadQuery query;

    private CachedHearingPayloadAdapter adapter;

    private static DistributionCommand command() {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34"),
                HEARING_ID,
                HEARING_DAY,
                Instant.parse("2026-08-21T08:00:00Z"),
                "Hearing_Resulted");
    }

    private static JsonNode payload(final String note) {
        return MAPPER.readTree("{\"hearing\":{\"id\":\"" + HEARING_ID + "\",\"note\":\"" + note
                + "\"},\"sharedTime\":\"2026-08-21T08:00:00Z\"}");
    }

    @BeforeEach
    void setUp() {
        adapter = new CachedHearingPayloadAdapter(cache, query, PREFIX);
    }

    @Nested
    @DisplayName("cache first")
    class CacheFirst {

        @Test
        void fetch_should_return_the_payload_the_dated_key_holds() {
            final JsonNode cached = payload("from the cache");
            when(cache.read(DATED_KEY)).thenReturn(Optional.of(cached));

            assertThat(adapter.fetch(command())).isSameAs(cached);
        }

        @Test
        void fetch_should_not_touch_the_query_side_when_the_cache_answered() {
            when(cache.read(DATED_KEY)).thenReturn(Optional.of(payload("from the cache")));

            adapter.fetch(command());

            verifyNoInteractions(query);
        }

        @Test
        void fetch_should_not_read_the_legacy_key_when_the_dated_key_answered() {
            when(cache.read(DATED_KEY)).thenReturn(Optional.of(payload("from the cache")));

            adapter.fetch(command());

            verify(cache, never()).read(LEGACY_KEY);
        }
    }

    @Nested
    @DisplayName("legacy key — registered deviation 4")
    class RegisteredDeviations {

        /**
         * Registered deviation 4. The function app builds one key from the hearing date it was given
         * and reads it once; this reads the legacy undated twin as well, because the producer
         * publishes the payload under both forms (design doc §2.1; {@code doc/API_CONTRACTS.md}
         * field semantics for {@code hearingDay}; Option 2 page row 1, "both key forms"). Reading
         * only the dated form would send every legacy-cached hearing to the query API, which
         * answers — so the miss would cost a round trip and show up nowhere.
         *
         * <p>Asserted here rather than left implicit, because an unregistered difference from the
         * function app fails the deviations gate whichever direction it runs in.
         */
        @Test
        void fetch_should_read_the_legacy_key_when_the_dated_key_is_absent() {
            final JsonNode cached = payload("under the legacy key");
            when(cache.read(DATED_KEY)).thenReturn(Optional.empty());
            when(cache.read(LEGACY_KEY)).thenReturn(Optional.of(cached));

            assertThat(adapter.fetch(command())).isSameAs(cached);
        }

        @Test
        void fetch_should_not_touch_the_query_side_when_the_legacy_key_answered() {
            when(cache.read(DATED_KEY)).thenReturn(Optional.empty());
            when(cache.read(LEGACY_KEY)).thenReturn(Optional.of(payload("legacy")));

            adapter.fetch(command());

            verifyNoInteractions(query);
        }

        /**
         * The whole of the difference, stated as a count: two lookups where the function app makes
         * one. Pinned so that removing the second — or adding a third — is a decision somebody has
         * to take against this register entry rather than a quiet edit.
         */
        @Test
        void fetch_should_read_exactly_the_two_registered_key_forms_and_no_others() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.of(payload("from the query api")));

            adapter.fetch(command());

            verify(cache).read(DATED_KEY);
            verify(cache).read(LEGACY_KEY);
            verifyNoMoreInteractions(cache);
        }
    }

    @Nested
    @DisplayName("query-side fallback")
    class Fallback {

        @Test
        void fetch_should_ask_the_query_side_when_neither_key_answered() {
            final JsonNode queried = payload("from the query api");
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.of(queried));

            assertThat(adapter.fetch(command())).isSameAs(queried);
        }

        @Test
        void fetch_should_pass_the_command_through_to_the_query_side() {
            final DistributionCommand command = command();
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(command)).thenReturn(Optional.of(payload("from the query api")));

            assertThat(adapter.fetch(command)).isNotNull();
        }

        /**
         * A cache that cannot answer is a cache with nothing in it, and the cache adapter is what
         * says so — see {@link LettuceHearingPayloadCache}, which absorbs its own technology's
         * failures and reports the miss. Here that arrives as an empty read, and the query side gets
         * its turn exactly as it does for an absent key.
         */
        @Test
        void fetch_should_ask_the_query_side_when_the_cache_reported_nothing_because_it_is_down() {
            final JsonNode queried = payload("from the query api");
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.of(queried));

            assertThat(adapter.fetch(command())).isSameAs(queried);
        }
    }

    @Nested
    @DisplayName("a failure that is not the cache's own")
    class Unexpected {

        /**
         * Nothing is caught here. A cache implementation absorbs the failures of its own technology
         * and reports them as a miss; anything else reaching this frame is a defect in this service,
         * and turning it into a fallback would spend a query-side round trip hiding it. It escapes
         * to the pipeline, which records it and releases the claim.
         */
        @Test
        void fetch_should_let_an_unexpected_failure_out_rather_than_absorb_it() {
            when(cache.read(DATED_KEY)).thenThrow(new IllegalStateException("a defect, not a miss"));

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void fetch_should_not_reach_the_query_side_after_an_unexpected_failure() {
            when(cache.read(DATED_KEY)).thenThrow(new IllegalStateException("a defect, not a miss"));

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(query);
        }
    }

    @Nested
    @DisplayName("neither source answered")
    class NeitherAnswered {

        @Test
        void fetch_should_raise_a_payload_failure_when_no_source_supplied_a_payload() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .isInstanceOf(PayloadUnavailableException.class);
        }

        @Test
        void fetch_should_classify_an_unavailable_payload_as_transient() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.type(
                                    PayloadUnavailableException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(ReasonCode.PIPELINE_TRANSIENT_FAILURE);
                    });
        }

        /**
         * The reason travels into {@code processed_request.failure_reason}, a dead-letter description
         * and the log index, so it must be the bounded code and nothing else — no key, no hearing
         * identifier, no message from the layer beneath.
         */
        @Test
        void fetch_should_carry_only_the_bounded_reason_code_in_its_message() {
            when(cache.read(any())).thenReturn(Optional.empty());
            when(query.fetch(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.fetch(command()))
                    .hasMessage(ReasonCode.PIPELINE_TRANSIENT_FAILURE.code());
        }
    }
}
