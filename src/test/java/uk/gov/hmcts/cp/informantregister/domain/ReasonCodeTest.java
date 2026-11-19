package uk.gov.hmcts.cp.informantregister.domain;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fromCode} is the inverse of {@link ReasonCode#code()} — used by a re-park (spec FR-009)
 * to turn a {@code processed_request.failure_reason} read back off the row into the bounded
 * reason it recorded, total over every declared value and never throwing on one it does not
 * recognise.
 */
class ReasonCodeTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(ReasonCode.class)
    @DisplayName("every declared reason code round-trips through its own stored code")
    void every_reason_code_should_round_trip(final ReasonCode reason) {
        assertThat(ReasonCode.fromCode(reason.code())).contains(reason);
    }

    @Test
    @DisplayName("an unrecognised stored code returns empty, never throws")
    void an_unrecognised_code_should_return_empty() {
        assertThat(ReasonCode.fromCode("NOT_A_REAL_REASON_CODE")).isEmpty();
    }

    @Test
    @DisplayName("a null stored code returns empty, never throws")
    void a_null_code_should_return_empty() {
        assertThat(ReasonCode.fromCode(null)).isEqualTo(Optional.empty());
    }
}
