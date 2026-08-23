package uk.gov.hmcts.cp.informantregister.config;

import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the packaged configuration is allowed to carry.
 *
 * <p>The broker credential is the one setting whose packaged default has already cost a deployed
 * override: when {@code application.yaml} shipped the emulator's development connection string,
 * every deployed environment had to carry a <em>blank</em>
 * {@code INFORMANTREGISTER_SERVICEBUS_CONNECTIONSTRING} purely to erase it, because startup
 * enforces exactly one credential source and the namespace is the deployed one. A blank override
 * is exactly the line a reviewer tidies away, and tidying it away breaks startup. So the packaged
 * file may name the property but must give it no value: local runs, the compose file, the
 * quickstart and the test suites each supply the emulator string themselves, and a deployment
 * that supplies neither source fails fast with the validator's own message instead of silently
 * inheriting a credential for a broker that does not exist there.
 */
@DisplayName("The packaged configuration carries no broker credential")
class PackagedDefaultsTest {

    @Test
    @SuppressWarnings("unchecked")
    void packaged_application_yaml_should_carry_no_connection_string_value() {
        final Map<String, Object> root;
        try (InputStream packaged =
                PackagedDefaultsTest.class.getResourceAsStream("/application.yaml")) {
            // Single-document load on purpose: the shipped file is one document today, and if it
            // ever grows a `---`-separated profile document, load() throws rather than silently
            // reading only the first — this test then fails loudly and gets extended, instead of
            // a profile-scoped credential slipping past it.
            root = new Yaml().load(packaged);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("packaged application.yaml unreadable", unreadable);
        }

        final Map<String, Object> informantregister =
                (Map<String, Object>) root.get("informantregister");
        final Map<String, Object> servicebus =
                (Map<String, Object>) informantregister.get("servicebus");
        final Object connectionString = servicebus.get("connection-string");

        assertThat(connectionString == null || String.valueOf(connectionString).isBlank())
                .as("the packaged connection-string must be absent or blank — the emulator "
                        + "string belongs to compose/quickstart/tests, never to the jar")
                .isTrue();
    }
}
