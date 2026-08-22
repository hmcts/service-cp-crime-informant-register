package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every setting this service owns, bound once and typed.
 *
 * <p>Defaults live here rather than only in {@code application.yaml}, so the values are visible to
 * the code that depends on them and a missing configuration file cannot silently change behaviour.
 * The two credential settings are the deliberate exception: neither has a default, because a service
 * that invents a broker address is a service that can talk to the wrong broker.
 *
 * @param consumer   whether intake runs at all
 * @param servicebus broker connection and consumer settings
 * @param claim      the single-runner claim's timings
 * @param store      processed-log store probing
 * @param results    the outbound Results command API
 * @param stub       test-only control over the stub adapters
 */
@ConfigurationProperties(prefix = "informantregister")
public record InformantRegisterProperties(
        @DefaultValue Consumer consumer,
        @DefaultValue Servicebus servicebus,
        @DefaultValue Claim claim,
        @DefaultValue Store store,
        @DefaultValue Results results,
        @DefaultValue Stub stub) {

    /**
     * Master switch for the Service Bus consumer.
     *
     * @param enabled master switch for starting the processor at all; false in the test profile
     */
    public record Consumer(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Connection, settlement and health settings for the inbound queue.
     *
     * @param connectionString          local and CI only, emulator connection string
     * @param namespace                 deployed only, fully qualified namespace for workload identity
     * @param queueName                 the inbound queue
     * @param maxConcurrentCalls        processor concurrency
     * @param maxDeliveryCount          mirrors the broker queue setting; recognises the final delivery
     * @param maxAutoLockRenewDuration  must outlive any legitimate run
     * @param healthStaleness           age past which an unresolved error with no traffic stops
     *                                  being reported as an outage
     */
    public record Servicebus(
            String connectionString,
            String namespace,
            @DefaultValue("informantregister.requests") String queueName,
            @DefaultValue("2") int maxConcurrentCalls,
            @DefaultValue("5") int maxDeliveryCount,
            @DefaultValue("5m") Duration maxAutoLockRenewDuration,
            @DefaultValue("60s") Duration healthStaleness) {
    }

    /**
     * Claim timing: how long a claim lives and how long a run may take inside it.
     *
     * @param lease             claim expiry, written as {@code now() + lease}
     * @param processingDeadline enforced run bound, strictly shorter than the lease
     */
    public record Claim(
            @DefaultValue("5m") Duration lease,
            @DefaultValue("4m") Duration processingDeadline) {
    }

    /**
     * Processed-log availability probing.
     *
     * @param probeInterval store-health probe interval, driving start and resume
     */
    public record Store(@DefaultValue("10s") Duration probeInterval) {
    }

    /**
     * The Results command API this service POSTs {@code add-informant-register} to.
     *
     * <p>{@code baseUrl} and {@code systemUserId} follow the broker credentials' rule and carry no
     * default here: an endpoint a service invents is an endpoint it can talk to by mistake, and the
     * identity is a secret that arrives from Key Vault. The local development value in
     * {@code application.yaml} is the Results command API's own declared {@code baseUri}, not a
     * value chosen here.
     *
     * <p>{@code headers} exists because the identity requirement is documented and the
     * <em>authorisation</em> requirement is not. {@code doc/API_CONTRACTS.md} names {@code CJSCPPUID}
     * and nothing else, while the command's access-control rules on the Results side require the
     * caller to be in a named user group. Rather than guess at a scheme, every additional header is
     * configuration: whatever the mesh turns out to need can be supplied without a code change, and
     * nothing is invented in the meantime.
     *
     * @param maxAttempts     total POST attempts per authority, the first included
     * @param initialBackoff  the first wait between retryable attempts; doubled each time
     * @param maxBackoff      the ceiling on any wait, a {@code Retry-After} the server asked for
     *                        included, so a hostile or mistaken header cannot park a run past its
     *                        claim
     * @param connectTimeout  how long to wait for the connection
     * @param readTimeout     how long to wait for the response once connected
     * @param baseUrl         scheme, host and port of the Results context, no path
     * @param systemUserId    the {@code CJSCPPUID} identity; a secret, never logged
     * @param headers         any further headers the mesh requires, name to value
     */
    public record Results(
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("500ms") Duration initialBackoff,
            @DefaultValue("20s") Duration maxBackoff,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {

        /** Freezes the header map, and treats an unconfigured one as none rather than as absent. */
        public Results {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * Stub adapter behaviour, for the test and local profiles only.
     *
     * @param payloadFailureMode the simulated payload failure; test and local profiles only
     */
    public record Stub(@DefaultValue("NONE") PayloadFailureMode payloadFailureMode) {
    }
}
