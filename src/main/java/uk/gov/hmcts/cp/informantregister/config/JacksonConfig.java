package uk.gov.hmcts.cp.informantregister.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>The feature is applied in two places for one reason: {@link #applyContractDefaults} is the
 * single definition, and both the application's auto-configured mapper (through the customizer bean
 * below) and the standalone mapper unit tests build (through {@link #contractObjectMapper()}) are
 * built by it. Configuring only the standalone factory would have left the running service reading
 * money as binary floating point while the unit tests stayed green.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /**
     * Applies this service's JSON contract to any Jackson 3 mapper builder.
     *
     * <p>Big decimals for floating-point values: inbound hearing payloads carry monetary amounts,
     * and binary floating point cannot represent them exactly. Every fractional number therefore
     * materialises as a {@code BigDecimal}-backed node and round-trips digit for digit.
     */
    public static JsonMapper.Builder applyContractDefaults(final JsonMapper.Builder builder) {
        return builder.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * Applies the same contract to the mapper Spring Boot auto-configures, which is the one every
     * bean that injects an {@code ObjectMapper} receives.
     */
    @Bean
    public JsonMapperBuilderCustomizer informantRegisterJsonMapperBuilderCustomizer() {
        return JacksonConfig::applyContractDefaults;
    }

    /**
     * A standalone mapper carrying the same configuration, for code constructed outside a Spring
     * context — unit tests, chiefly. A context test pins it to the injected bean so the two cannot
     * drift.
     */
    public static ObjectMapper contractObjectMapper() {
        return applyContractDefaults(JsonMapper.builder()).build();
    }
}
