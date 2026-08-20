package uk.gov.hmcts.cp.informantregister.config;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared JSON mapper configuration.
 *
 * <p>Spring Boot 4.1 supplies Jackson 3, so the tree model is {@code tools.jackson.databind.JsonNode}
 * and the deserialisation feature that keeps monetary values exact is
 * {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} — the constant name is unchanged from
 * Jackson 2, only the package moved.
 *
 * <p>Configured once here, and once only, because a second mapper with different number handling is
 * how a fraction of a penny gets lost between two parts of the same service.
 */
public final class JacksonConfig {

    private JacksonConfig() {
        // Factory holder.
    }

    /**
     * The mapper every part of this service shares.
     *
     * <p>Big decimals for floating-point values: inbound hearing payloads carry monetary amounts,
     * and binary floating point cannot represent them exactly. Every fractional number therefore
     * materialises as a {@code BigDecimal}-backed node and round-trips digit for digit.
     */
    public static ObjectMapper contractObjectMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }
}
