package uk.gov.hmcts.cp.informantregister.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.config.JacksonConfig;

/**
 * Reads the legacy {@code NowsHelper} Jest fixtures the helper twins run against.
 *
 * <p>The files under {@code src/test/resources/fixtures/nowshelper/} are byte-identical copies of
 * {@code NowsHelper/service/test/}, verified with {@code diff} at copy time, as constitution
 * Principle I requires. They are kept apart from {@code fixtures/setinformantregister/} because they
 * belong to a different legacy suite: those are the activity's own fixtures, these are the ones its
 * helpers are tested with, and mixing them would make it impossible to tell at a glance which suite
 * a golden answers to.
 *
 * <p>They are read through the service's own contract mapper, so a fixture reaches the port exactly
 * as a fetched hearing payload would — {@code USE_BIG_DECIMAL_FOR_FLOATS} included. Reading them with
 * a default mapper would compare the port against a tree the port never sees.
 */
public final class LegacyFixtures {

    private static final String ROOT = "/fixtures/nowshelper/";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private LegacyFixtures() {
    }

    /**
     * Reads one legacy fixture.
     *
     * @param name the file name, as it is named in the legacy suite
     * @return the parsed tree
     */
    public static JsonNode read(final String name) {
        final String resource = ROOT + name;
        try (InputStream stream = LegacyFixtures.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
