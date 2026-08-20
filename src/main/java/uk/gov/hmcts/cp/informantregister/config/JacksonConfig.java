package uk.gov.hmcts.cp.informantregister.config;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared JSON mapper configuration.
 *
 * <p>Spring Boot 4.1 supplies Jackson 3, so the tree model is {@code tools.jackson.databind.JsonNode}
 * and the deserialisation feature that keeps monetary values exact is
 * {@code tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS} — the constant
 * name is unchanged from Jackson 2, only the package moved.
 */
public final class JacksonConfig {

    private JacksonConfig() {
        // Factory holder.
    }

    /**
     * The mapper every part of this service shares.
     */
    public static ObjectMapper contractObjectMapper() {
        return JsonMapper.builder().build();
    }
}
