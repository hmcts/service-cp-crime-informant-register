package uk.gov.hmcts.cp.simulation;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hearing payload a simulated hearing resolves to, whichever way the service fetches it: the
 * Redis claim check ({@link RedisPayloadSeeder}) or the Results query fallback
 * ({@link FallbackHearingStubs}). Both are built from {@code wiremock/mappings/hearing-payload.json}
 * so cache hits and misses transform into the same registers, and so {@code verify-outputs.sh} can
 * read the expected authorities from the same file.
 */
final class HearingPayloadFixture {

    private static final String MAPPING = "wiremock/mappings/hearing-payload.json";
    private static final String ANY_SUFFIX = ".*";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode TEMPLATE = load();

    private HearingPayloadFixture() {
    }

    /**
     * The hearing envelope with {@code hearing.id} set to this hearing.
     *
     * @param hearingId the hearing the envelope is for
     * @return a fresh, mutable copy of the envelope
     */
    static ObjectNode envelopeFor(final UUID hearingId) {
        final ObjectNode envelope = (ObjectNode) TEMPLATE.path("response").path("jsonBody").deepCopy();
        ((ObjectNode) envelope.path("hearing")).put("id", hearingId.toString());
        return envelope;
    }

    /**
     * A WireMock mapping that answers exactly this hearing's query-API GET with its envelope. It
     * keeps the shared mapping's status and headers, drops its response transformers (the static
     * body needs none), and outranks the shared catch-all mapping.
     *
     * @param hearingId the hearing the stub is for
     * @param priority  the WireMock priority; lower wins, and the catch-all has the default (5)
     * @return the mapping, ready to POST to {@code /__admin/mappings}
     */
    static ObjectNode stubFor(final UUID hearingId, final int priority) {
        final ObjectNode stub = MAPPER.createObjectNode();
        stub.put("priority", priority);
        stub.putObject("request")
                .put("method", TEMPLATE.path("request").path("method").asString())
                .put("urlPath", pathPrefix() + hearingId);
        final ObjectNode response = (ObjectNode) TEMPLATE.path("response").deepCopy();
        response.remove("transformers");
        response.set("jsonBody", envelopeFor(hearingId));
        stub.set("response", response);
        return stub;
    }

    private static String pathPrefix() {
        final String pattern = TEMPLATE.path("request").path("urlPathPattern").asString();
        return pattern.substring(0, pattern.length() - ANY_SUFFIX.length());
    }

    private static JsonNode load() {
        try (InputStream in = HearingPayloadFixture.class.getClassLoader().getResourceAsStream(MAPPING)) {
            if (in == null) {
                throw new IllegalStateException("Missing hearing-payload WireMock mapping");
            }
            final JsonNode mapping = MAPPER.readTree(in);
            if (!(mapping.path("response").path("jsonBody").path("hearing") instanceof ObjectNode)) {
                throw new IllegalStateException("Hearing-payload mapping has no JSON hearing envelope");
            }
            if (!mapping.path("request").path("urlPathPattern").asString().endsWith(ANY_SUFFIX)) {
                throw new IllegalStateException(
                        "Hearing-payload mapping's urlPathPattern must end in " + ANY_SUFFIX
                                + " so a per-hearing path can be derived from it");
            }
            return mapping;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read hearing-payload WireMock mapping", e);
        }
    }
}
