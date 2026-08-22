package uk.gov.hmcts.cp.informantregister.adapter.payload;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The hearing payload cache over Lettuce.
 *
 * <p>One string {@code GET} and a parse. The function app's client settings that carried real
 * meaning — TLS, a connect timeout, a keep-alive — belong to the {@link RedisClient} handed in here;
 * the ones that never worked are not ported. Its {@code retry_strategy} block referenced an
 * out-of-scope variable and was not honoured by the client library in any case (design defect D13),
 * so Lettuce's own reconnection replaces it rather than a transcription of dead code.
 *
 * <p>TLS certificate verification is the one setting that is deliberately <em>not</em> a
 * transcription: the function app connects with {@code rejectUnauthorized: false}, and this does
 * not. That is registered deviation 1 in {@code doc/DEVIATIONS.md}, pre-approved at ratification,
 * and it changes nothing about what a register contains.
 *
 * <p>Failures are not caught here. A cache that cannot be reached is the composite adapter's
 * decision to make, and it makes it once — see {@link CachedHearingPayloadAdapter}. The single
 * exception is a value that will not parse, which is reported as a miss because that is exactly what
 * {@code getResultFromCache} does with it: the {@code JSON.parse} sits inside the catch that returns
 * {@code null}.
 */
public class LettuceHearingPayloadCache implements HearingPayloadCache, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LettuceHearingPayloadCache.class);

    private final RedisClient client;
    private final ObjectMapper objectMapper;

    private volatile StatefulRedisConnection<String, String> connection;

    /**
     * Wraps an already-configured client.
     *
     * @param client       the Redis client, carrying the address, credentials, TLS and timeouts
     * @param objectMapper the shared mapper, so a cached payload is read exactly as any other JSON
     */
    public LettuceHearingPayloadCache(final RedisClient client, final ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<JsonNode> read(final String key) {
        final String cached = openConnection().sync().get(key);
        Optional<JsonNode> payload = Optional.empty();
        if (cached != null && !cached.isBlank()) {
            try {
                final JsonNode parsed = objectMapper.readTree(cached);
                if (parsed != null && !parsed.isNull() && !parsed.isMissingNode()) {
                    payload = Optional.of(parsed);
                }
            } catch (JacksonException unparseable) {
                // The key exists and its value is not a payload. Left as a miss, exactly as the
                // function app leaves it, so the query side still gets its turn — and logged,
                // because a cache the producer is corrupting should not have to be discovered from
                // a traffic graph.
                LOG.warn("A cached hearing payload could not be parsed; treating it as absent.",
                        unparseable);
            }
        }
        return payload;
    }

    /**
     * Returns the connection, opening one when there is not a usable one.
     *
     * <p>Opened on first read rather than at construction: a cache that is down must not stop the
     * service from starting, because the query side can still answer and the processed log still
     * needs to record what happened. Once open, Lettuce's own reconnection keeps it that way; this
     * only has to notice a connection that has been closed for good.
     */
    private synchronized StatefulRedisConnection<String, String> openConnection() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection;
    }

    /**
     * Closes the connection if one is open.
     *
     * <p>The field is left pointing at the closed connection rather than cleared, because
     * {@link #openConnection()} already asks whether it is open — and a reference that survives is
     * how a read after a close reopens instead of failing on a handle nobody can revive.
     */
    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
        }
    }
}
