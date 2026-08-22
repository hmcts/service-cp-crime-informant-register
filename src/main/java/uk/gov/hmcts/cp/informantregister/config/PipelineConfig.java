package uk.gov.hmcts.cp.informantregister.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.adapter.stub.StubRegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;

/**
 * The application core and the adapters currently serving its ports.
 *
 * <p>The remaining stub is declared as its port type rather than as its own class, so replacing it
 * with a real adapter is a change to one method here and to nothing else. That is the claim the
 * skeleton made, and the payload port has now been through it: the adapter behind it moved out to
 * {@link LivePayloadConfig} and {@link StubPayloadConfig}, which choose between two implementations,
 * and nothing in {@link DistributionPipeline} changed to allow it.
 *
 * <p>Excluded from the {@code test} profile for the same reason as the processed-log wiring: the
 * pipeline needs the guard, the guard needs a store, and that profile has none.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class PipelineConfig {

    /**
     * The clock the run's processing deadline is measured against.
     *
     * <p>Local elapsed time only. No claim decision is made from it — those compare the database's
     * {@code now()} against stored timestamps, inside the database — so this clock cannot introduce
     * the multi-node skew the data model's single-time-authority rule rules out.
     */
    @Bean
    public Clock informantRegisterClock() {
        return Clock.systemUTC();
    }

    /**
     * The parser over the shared mapper, so the running service reads a body exactly as the contract
     * corpus does.
     */
    @Bean
    public DistributionCommandParser distributionCommandParser(final ObjectMapper objectMapper) {
        return new DistributionCommandParser(objectMapper);
    }

    /** The submission port, stubbed until the Results adapter story lands. */
    @Bean
    public RegisterSubmissionClient registerSubmissionClient() {
        return new StubRegisterSubmissionClient();
    }

    /** The use-case orchestrator, wired against ports only. */
    @Bean
    public DistributionPipeline distributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final RegisterSubmissionClient submissionClient,
            final ProcessingMetrics metrics,
            final Clock clock,
            final InformantRegisterProperties properties) {
        return new DistributionPipeline(guard, payloadSource, submissionClient, metrics, clock,
                properties.claim().processingDeadline());
    }
}
