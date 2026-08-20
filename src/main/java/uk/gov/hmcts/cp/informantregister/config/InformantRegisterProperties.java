package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;

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
 * @param stub       test-only control over the stub adapters
 */
@ConfigurationProperties(prefix = "informantregister")
public record InformantRegisterProperties(
        @DefaultValue Consumer consumer,
        @DefaultValue Servicebus servicebus,
        @DefaultValue Claim claim,
        @DefaultValue Store store,
        @DefaultValue Stub stub) {

    /**
     * @param enabled master switch for starting the processor at all; false in the test profile
     */
    public record Consumer(@DefaultValue("true") boolean enabled) {
    }

    /**
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
     * @param lease             claim expiry, written as {@code now() + lease}
     * @param processingDeadline enforced run bound, strictly shorter than the lease
     */
    public record Claim(
            @DefaultValue("5m") Duration lease,
            @DefaultValue("4m") Duration processingDeadline) {
    }

    /**
     * @param probeInterval store-health probe interval, driving start and resume
     */
    public record Store(@DefaultValue("10s") Duration probeInterval) {
    }

    /**
     * @param payloadFailureMode the simulated payload failure; test and local profiles only
     */
    public record Stub(@DefaultValue("NONE") PayloadFailureMode payloadFailureMode) {
    }
}
