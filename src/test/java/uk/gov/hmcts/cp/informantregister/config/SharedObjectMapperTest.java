package uk.gov.hmcts.cp.informantregister.config;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the mapper the <em>application</em> injects to Principle IV, not a mapper a test built for
 * itself.
 *
 * <p>A static factory can be configured perfectly and still leave the running service using Spring's
 * auto-configured mapper, which knows nothing about it. Every collaborator that takes an
 * `ObjectMapper` from the context — the message listener among them — would then read monetary
 * values as binary floating point while the unit tests stayed green. So this asserts the injected
 * bean, and deliberately does not construct one.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("the application's shared ObjectMapper")
class SharedObjectMapperTest {

    private final ObjectMapper objectMapper;

    // Constructor injection, in a test as in production: Principle V forbids field injection
    // everywhere, and a test that takes its collaborator through the constructor is a test that
    // could not accidentally run against a half-built context.
    @Autowired
    SharedObjectMapperTest(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Test
    void should_materialise_a_fractional_number_as_a_big_decimal() {
        final JsonNode tree = objectMapper.readTree("{\"amount\": 1234.56}");

        assertThat(tree.get("amount").isBigDecimal()).isTrue();
        assertThat(tree.get("amount").decimalValue()).isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    void should_not_lose_a_digit_of_a_high_precision_number() {
        // Binary floating point cannot hold this exactly. A register that rounds a penny is a
        // register that is wrong.
        final JsonNode tree = objectMapper.readTree("{\"amount\": 0.1234567890123456789}");

        assertThat(tree.get("amount").decimalValue()).isEqualTo(new BigDecimal("0.1234567890123456789"));
    }

    @Test
    void should_agree_with_the_mapper_the_parser_is_given_in_unit_tests() {
        // The two must not drift: if the standalone factory and the context ever disagree about
        // number handling, one of the two suites is proving nothing.
        final String body = "{\"amount\": 9.99}";

        assertThat(objectMapper.readTree(body).get("amount").decimalValue())
                .isEqualTo(JacksonConfig.contractObjectMapper().readTree(body).get("amount").decimalValue());
        assertThat(objectMapper.readTree(body).get("amount").isBigDecimal())
                .isEqualTo(JacksonConfig.contractObjectMapper().readTree(body).get("amount").isBigDecimal());
    }
}
