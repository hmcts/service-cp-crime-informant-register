package uk.gov.hmcts.cp.simulation;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.servicebus.jms.ServiceBusJmsConnectionFactory;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a Jakarta JMS {@link ConnectionFactory} for publishing test messages to Azure Service Bus.
 *
 * <p>Two auth modes, selected by system properties. An explicit namespace takes precedence over
 * a connection string:
 * <ul>
 *   <li><b>Entra ID (DEV / STE):</b> {@code -Dgatling.asbNamespace=sbdevccm01.servicebus.windows.net}
 *       — uses {@link ServiceBusJmsConnectionFactory} with {@code DefaultAzureCredential} (CBS
 *       token auth over AMQP). Requires {@code az login} or a service principal environment.
 *   <li><b>SAS / emulator (local):</b> {@code -Dgatling.connectionString=Endpoint=sb://...}, or
 *       composed by the build from {@code -Dgatling.emulatorKey} / {@code ASB_EMULATOR_KEY}
 *       — uses Qpid JMS {@link JmsConnectionFactory} with SASL PLAIN (SAS key name + key).
 * </ul>
 *
 * <p>All CPP ASB namespaces have {@code local_auth_enabled = false} (no SAS keys). The SAS
 * path exists only for the local Service Bus emulator.
 */
final class ServiceBusConnectionFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusConnectionFactory.class);

    private ServiceBusConnectionFactory() {
    }

    static ConnectionFactory create() {
        String connectionString = System.getProperty("gatling.connectionString", "");
        String namespace = System.getProperty("gatling.asbNamespace", "");

        if (!namespace.isBlank()) {
            LOG.info("ASB auth: DefaultAzureCredential → {}", namespace);
            return fromDefaultCredential(namespace);
        }
        if (!connectionString.isBlank()) {
            LOG.info("ASB auth: SAS connection string (emulator/local)");
            return fromConnectionString(connectionString);
        }
        throw new IllegalStateException(
                "Set -Dgatling.connectionString or ASB_EMULATOR_KEY (emulator/local) "
                + "or -Dgatling.asbNamespace (DEV/STE, requires az login)");
    }

    /**
     * Fails fast, before any load, if this run cannot publish to the queue.
     *
     * <p>Opens a connection, a session and a producer on the queue — the producer attach is where
     * the broker checks the credential's token and the entity's send rights — then closes them
     * without sending. Without it a credential the namespace rejects surfaces only as every publish
     * failing for the whole simulation (build 962633: thirteen minutes of {@code InvalidIssuer}).
     *
     * @param queueName the queue the simulation publishes to
     * @throws IllegalStateException if the connection or the producer cannot be opened
     */
    static void verifyCanSend(String queueName) {
        try (Connection connection = create().createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageProducer producer = session.createProducer(session.createQueue(queueName))) {
            LOG.info("ASB publish check passed: producer opened on {}", queueName);
        } catch (JMSException e) {
            throw new IllegalStateException("ABORTING: cannot publish to " + queueName + ": "
                    + e.getMessage(), e);
        }
    }

    private static ConnectionFactory fromDefaultCredential(String namespace) {
        return new ServiceBusJmsConnectionFactory(
                new DefaultAzureCredentialBuilder().build(),
                namespace,
                null);
    }

    private static ConnectionFactory fromConnectionString(String connectionString) {
        Map<String, String> parts = parseConnectionString(connectionString);

        String endpoint = parts.getOrDefault("Endpoint", "");
        String keyName = parts.getOrDefault("SharedAccessKeyName", "");
        String key = parts.getOrDefault("SharedAccessKey", "");
        boolean isEmulator = "true".equalsIgnoreCase(
                parts.getOrDefault("UseDevelopmentEmulator", "false"));

        String host = endpoint
                .replace("sb://", "")
                .replaceAll("[/;]+$", "");

        String scheme = isEmulator ? "amqp" : "amqps";
        int port = isEmulator ? 5672 : 5671;

        String remoteUri = "%s://%s:%d?amqp.saslMechanisms=PLAIN&jms.username=%s&jms.password=%s"
                .formatted(
                        scheme,
                        host,
                        port,
                        URLEncoder.encode(keyName, StandardCharsets.UTF_8),
                        URLEncoder.encode(key, StandardCharsets.UTF_8));

        return new JmsConnectionFactory(remoteUri);
    }

    private static Map<String, String> parseConnectionString(String connectionString) {
        Map<String, String> result = new HashMap<>();
        for (String segment : connectionString.split(";")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String k = trimmed.substring(0, eq);
                String v = trimmed.substring(eq + 1);
                result.put(k, v);
            }
        }
        return result;
    }
}
