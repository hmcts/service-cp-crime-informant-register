package uk.gov.hmcts.cp.informantregister.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The explicit, reviewed mapping a re-park uses when a {@code FAILED} row has no recorded
 * {@code parking_category} — a legacy row, or one written by an older consumer mid-rollout (spec
 * FR-010). Total and pure: every declared {@link ReasonCode} and any unrecognised stored text both
 * map to something, and neither ever throws.
 */
class FailureCategoryMappingTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ReasonCode.class, names = {
        "SUBMISSION_REJECTED", "SUBMISSION_NOT_ACCEPTED", "TRANSFORMATION_FAILED"
    })
    @DisplayName("the non-transient path's own reasons map to NON_TRANSIENT")
    void the_non_transient_reasons_should_map_to_non_transient(final ReasonCode reason) {
        assertThat(FailureCategoryMapping.categoryFor(reason.code()))
                .isEqualTo(DeadLetterReason.NON_TRANSIENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ReasonCode.class, names = {
        "SUBMISSION_REJECTED", "SUBMISSION_NOT_ACCEPTED", "TRANSFORMATION_FAILED"
    }, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every other declared reason maps to EXHAUSTED")
    void every_other_declared_reason_should_map_to_exhausted(final ReasonCode reason) {
        assertThat(FailureCategoryMapping.categoryFor(reason.code()))
                .isEqualTo(DeadLetterReason.EXHAUSTED);
    }

    @Test
    @DisplayName("an unrecognised stored reason maps to EXHAUSTED, never throws")
    void an_unrecognised_reason_should_map_to_exhausted() {
        assertThat(FailureCategoryMapping.categoryFor("NOT_A_REAL_REASON_CODE"))
                .isEqualTo(DeadLetterReason.EXHAUSTED);
    }

    @Test
    @DisplayName("a null stored reason maps to EXHAUSTED, never throws")
    void a_null_reason_should_map_to_exhausted() {
        assertThat(FailureCategoryMapping.categoryFor(null)).isEqualTo(DeadLetterReason.EXHAUSTED);
    }
}
