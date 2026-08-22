package uk.gov.hmcts.cp.informantregister.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.informantregister.adapter.payload.HearingPayloadCache;
import uk.gov.hmcts.cp.informantregister.adapter.payload.HearingPayloadQuery;
import uk.gov.hmcts.cp.informantregister.adapter.payload.LettuceHearingPayloadCache;
import uk.gov.hmcts.cp.informantregister.adapter.payload.ResultsQueryHearingPayloadClient;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;

/**
 * The real payload source: the cache, and the query side behind it.
 *
 * <p>Selected by {@code informantregister.payload.mode}, and selected by default — a service that
 * has to be told to fetch payloads is a service that will one day be deployed not fetching them.
 * {@link StubPayloadConfig} is the other half of the pair, and exactly one of the two contributes a
 * bean.
 *
 * <p>The two clients are separate beans rather than locals inside the adapter's method, because both
 * hold connections that have to be released. Declared this way, Spring closes them at shutdown; built
 * inside a method they would leak an event-loop group and a connection pool on every context close,
 * which the container suites do dozens of times per build.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the pipeline wiring, which that
 * profile has no store for.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "informantregister.payload", name = "mode", havingValue = "LIVE",
        matchIfMissing = true)
public class LivePayloadConfig {

    /**
     * The Lettuce client for the payload cache.
     *
     * <p>TLS is configured from settings and its certificates are verified. The function app
     * connects with {@code rejectUnauthorized: false}; not porting that is registered deviation 1,
     * pre-approved at ratification, and it changes nothing a register contains.
     *
     * <p>Creating the client opens nothing — the connection is made on the first read — so a cache
     * that is down cannot stop the service from starting and reporting why.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient informantRegisterRedisClient(final InformantRegisterProperties properties) {
        final InformantRegisterProperties.Redis redis = properties.payload().redis();
        final RedisURI.Builder uri = RedisURI.builder()
                .withHost(redis.host())
                .withPort(redis.port())
                .withSsl(redis.ssl())
                .withVerifyPeer(redis.ssl())
                .withTimeout(redis.commandTimeout());
        if (redis.password() != null && !redis.password().isBlank()) {
            uri.withPassword(redis.password().toCharArray());
        }
        final RedisClient client = RedisClient.create(uri.build());
        client.setOptions(client.getOptions().mutate()
                .socketOptions(client.getOptions().getSocketOptions().mutate()
                        .connectTimeout(redis.connectTimeout())
                        .build())
                .build());
        return client;
    }

    /** The payload cache. Closed at shutdown: the bean instance is {@link AutoCloseable}. */
    @Bean
    public HearingPayloadCache hearingPayloadCache(final RedisClient redisClient,
            final ObjectMapper objectMapper) {
        return new LettuceHearingPayloadCache(redisClient, objectMapper);
    }

    /**
     * The query-side fallback.
     *
     * <p>Both timeouts are set deliberately. A read with no read timeout can outlive the run's
     * processing deadline and then its claim, which turns a slow query side into a request that two
     * runners believe they own.
     */
    @Bean
    public HearingPayloadQuery hearingPayloadQuery(final InformantRegisterProperties properties,
            final ObjectMapper objectMapper) {
        final InformantRegisterProperties.Fallback fallback = properties.payload().fallback();
        return new ResultsQueryHearingPayloadClient(
                RestClient.builder()
                        .baseUrl(properties.results().baseUrl())
                        .requestFactory(requestFactory(fallback.connectTimeout(),
                                fallback.readTimeout()))
                        .build(),
                properties.systemUserId(),
                objectMapper,
                fallback.maxAttempts(),
                fallback.retryInterval());
    }

    /** The payload port, served by the cache with the query side behind it. */
    @Bean
    public HearingPayloadSource hearingPayloadSource(final HearingPayloadCache cache,
            final HearingPayloadQuery query, final InformantRegisterProperties properties) {
        return new CachedHearingPayloadAdapter(cache, query,
                properties.payload().redis().keyPrefix());
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client: this is one small GET on a cache miss, and a
     * pooled client would hold background threads for the lifetime of every context - which the
     * container suites create and close dozens of times in a build.
     */
    private static ClientHttpRequestFactory requestFactory(
            final Duration connectTimeout, final Duration readTimeout) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
