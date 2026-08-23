package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.adapter.refdata.ReferenceDataNowSubscriptionsClient;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;

/**
 * The real now-subscriptions source: the reference-data query API.
 *
 * <p>Selected by {@code informantregister.referencedata.mode}, and selected by default — a service
 * that has to be told where its register's recipients come from is a service that will one day be
 * deployed not asking. {@link StubSubscriptionsConfig} is the other half of the pair, and exactly one
 * of the two contributes a bean.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the pipeline wiring, which that
 * profile has no store for.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "informantregister.referencedata", name = "mode",
        havingValue = "LIVE", matchIfMissing = true)
public class LiveSubscriptionsConfig {

    /**
     * The subscriptions port, served by the reference-data query API.
     *
     * <p>Both timeouts are set deliberately, for the reason the payload fallback sets both: a read
     * with no read timeout can outlive the run's processing deadline and then its claim, which turns
     * a slow reference-data server into a request two runners believe they own.
     *
     * @param properties   the bound settings
     * @param objectMapper the shared mapper, so an answer is read exactly as any other JSON is
     * @return the port
     */
    @Bean
    public NowSubscriptionsSource nowSubscriptionsSource(
            final InformantRegisterProperties properties, final ObjectMapper objectMapper) {

        final InformantRegisterProperties.Referencedata settings = properties.referencedata();
        return new ReferenceDataNowSubscriptionsClient(
                RestClient.builder()
                        .baseUrl(settings.baseUrl())
                        .requestFactory(requestFactory(settings.connectTimeout(),
                                settings.readTimeout()))
                        .build(),
                settings.systemUserId(),
                settings.headers(),
                objectMapper,
                settings.maxAttempts(),
                settings.retryInterval());
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client: this is one small GET per hearing that
     * produced a register, and a pooled client would hold background threads for the lifetime of
     * every context — which the container suites create and close dozens of times in a build.
     */
    private static ClientHttpRequestFactory requestFactory(
            final Duration connectTimeout, final Duration readTimeout) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
