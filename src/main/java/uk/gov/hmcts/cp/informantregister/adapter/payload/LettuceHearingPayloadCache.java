package uk.gov.hmcts.cp.informantregister.adapter.payload;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.observability.FaultSummary;

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
 * <p>A cache that cannot answer is reported as a cache with nothing in it, and this is the one class
 * that may decide that. It catches {@link RedisException} — the cache technology's own hierarchy,
 * connection failures included — and nothing wider: a fault that is not the cache's is a fault
 * somebody has to see, and absorbing it here would hide a bug in this service behind a fallback.
 * The composite adapter above therefore catches nothing at all.
 *
 * <p>A value that will not parse is a miss for the same reason, and that half is straight parity:
 * {@code getResultFromCache} runs its {@code JSON.parse} inside the catch that returns {@code null}.
 * The other half is not — there, only the {@code GET} sits inside that catch, and a client that
 * could not connect threw out of the activity altogether, so the query side never got its turn and
 * the run reported success having produced nothing. That is registered deviation 5
 * ({@code doc/DEVIATIONS.md}).
 *
 * <p>Neither failure is logged with the words the library used. A parser quotes the token it choked
 * on, and the token is a fragment of a hearing — a name, an address, a URN — so the line carries the
 * failure's type and never its message (constitution Principle VII, and the same rule
 * {@code DistributionCommandParser} follows for an unreadable message body).
 */
public class LettuceHearingPayloadCache implements HearingPayloadCache, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LettuceHearingPayloadCache.class);

    private final RedisClient client;
    private final ObjectMapper objectMapper;

    /**
     * The last observed reachability of the cache, so the transition can be reported once.
     *
     * <p>Not health, and not a gate on anything: a cache miss and a dead cache are the same empty
     * answer to a caller, exactly as the fallback ordering requires. This exists only so the log
     * says "the cache went away" once, instead of saying "a key was absent" once per hearing and
     * leaving the difference to be inferred from the volume.
     */
    private final AtomicBoolean reachable = new AtomicBoolean(true);

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
        return cached(key).flatMap(this::parsed);
    }

    /**
     * Reads the key, treating a cache that cannot answer as a cache with nothing in it.
     *
     * <p>Scoped to {@link RedisException} on purpose. That hierarchy is every way this cache can
     * fail — refused connection, expired TLS certificate, wrong password, command timeout — and it
     * is nothing else, so an error that is not the cache's own escapes to be recorded rather than
     * being spent on a fallback that was never meant for it.
     *
     * <p>An absent key and an unreachable cache are the same empty answer here, which is the whole
     * point; they are told apart in the log, not in the return type.
     */
    private Optional<String> cached(final String key) {
        Optional<String> value;
        try {
            value = Optional.ofNullable(openConnection().sync().get(key));
            if (reachable.compareAndSet(false, true)) {
                LOG.info("The hearing payload cache is answering again.");
            }
        } catch (RedisException unreadable) {
            // Logged, because a cache outage should be read from a log rather than inferred from a
            // rise in query-side traffic — and logged by type, because the message may name the
            // address and the credentials the connection was attempted with.
            //
            // The per-hearing line stays, but on its own it is the wrong shape for the incident it
            // reports: a total outage writes one of these per hearing, all identical, and none of
            // them says "Redis is down" — that has to be inferred from the volume. So the
            // transition is reported separately and once, which is what an on-call engineer can
            // actually act on.
            if (reachable.compareAndSet(true, false)) {
                LOG.error("The hearing payload cache has become unreachable; every hearing read is "
                                + "now falling through to the results query API. type={}",
                        FaultSummary.typeChain(unreadable));
            }
            LOG.warn("The hearing payload cache could not answer; treating the key as absent. "
                    + "type={}", FaultSummary.typeChain(unreadable));
            value = Optional.empty();
        }
        return value;
    }

    /**
     * Parses a cached value, treating one that will not parse as a miss.
     *
     * <p>Parity: the function app's {@code JSON.parse} sits inside the catch that returns
     * {@code null}, so the query side still gets its turn. The parser's own words are not written
     * out — they quote the token it choked on, and that token is hearing content.
     */
    private Optional<JsonNode> parsed(final String cached) {
        Optional<JsonNode> payload = Optional.empty();
        if (!cached.isBlank()) {
            try {
                final JsonNode tree = objectMapper.readTree(cached);
                if (tree != null && !tree.isNull() && !tree.isMissingNode()) {
                    payload = Optional.of(tree);
                }
            } catch (JacksonException unparseable) {
                // Deliberately worded apart from the unreachable-cache line above: this is a
                // document that arrived and is malformed, which is the producer's defect, not the
                // cache tier's. They used to be two WARNs a support engineer could not tell apart.
                LOG.warn("A cached hearing payload arrived but could not be parsed; treating it as "
                        + "absent. type={}", FaultSummary.typeChain(unparseable));
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
