package uk.gov.hmcts.cp.informantregister.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.informantregister.adapter.results.ResultsCommandGateway;
import uk.gov.hmcts.cp.informantregister.adapter.results.ResultsRegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.adapter.stub.StubHearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.application.DistributionPipeline;
import uk.gov.hmcts.cp.informantregister.application.HearingPayloadSource;
import uk.gov.hmcts.cp.informantregister.application.IdempotencyGuard;
import uk.gov.hmcts.cp.informantregister.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.informantregister.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.informantregister.persistence.ProcessedOutputRepository;

/**
 * The application core and the adapters currently serving its ports.
 *
 * <p>The two stubs are declared as the port types rather than as their own classes, so replacing
 * either with a real adapter is a change to one method here and to nothing else. That is the whole
 * claim the skeleton makes, and stating it in the wiring is how it stays true.
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

    /** The payload-source port, stubbed until the Redis adapter story lands. */
    @Bean
    public HearingPayloadSource hearingPayloadSource(
            final InformantRegisterProperties properties, final ObjectMapper objectMapper) {
        return new StubHearingPayloadSource(properties, objectMapper);
    }

    /**
     * The {@code add-informant-register} transport, with its own retry policy.
     *
     * <p>A bean of its own rather than a field of the adapter, so the policy is configurable and
     * visible at the wiring rather than buried a constructor deeper. The wait is
     * {@link Thread#sleep(java.time.Duration)}; the suites substitute a recorder, which is the only
     * reason it is a parameter at all.
     */
    @Bean
    public ResultsCommandGateway resultsCommandGateway(final InformantRegisterProperties properties) {
        return new ResultsCommandGateway(properties.results(), Thread::sleep);
    }

    /**
     * The submission port.
     *
     * <p>The claim the skeleton made — that replacing a stub is a change to one method here and to
     * nothing else — is being cashed in: the return type is unchanged, the pipeline is untouched,
     * and the adapter behind it now writes {@code processed_output} and POSTs.
     */
    @Bean
    public RegisterSubmissionClient registerSubmissionClient(
            final ProcessedOutputRepository outputs,
            final ResultsCommandGateway gateway,
            final ObjectMapper objectMapper) {
        return new ResultsRegisterSubmissionClient(outputs, gateway, objectMapper);
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
