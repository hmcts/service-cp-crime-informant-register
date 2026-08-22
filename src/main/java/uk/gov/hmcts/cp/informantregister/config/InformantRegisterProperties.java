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
 * @param consumer     whether intake runs at all
 * @param servicebus   broker connection and consumer settings
 * @param claim        the single-runner claim's timings
 * @param store        processed-log store probing
 * @param stub         test-only control over the stub adapters
 * @param payload      where the hearing payload is read from
 * @param results      the results context this service reads from and posts to
 * @param systemUserId the system user identity downstream contexts authorise against; a secret,
 *                     mounted from Key Vault, and therefore without a default
 */
@ConfigurationProperties(prefix = "informantregister")
public record InformantRegisterProperties(
        @DefaultValue Consumer consumer,
        @DefaultValue Servicebus servicebus,
        @DefaultValue Claim claim,
        @DefaultValue Store store,
        @DefaultValue Stub stub,
        @DefaultValue Payload payload,
        @DefaultValue Results results,
        String systemUserId) {

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
     * Stub adapter behaviour, for the test and local profiles only.
     *
     * @param payloadFailureMode the simulated payload failure; test and local profiles only
     */
    public record Stub(@DefaultValue("NONE") PayloadFailureMode payloadFailureMode) {
    }

    /**
     * Where the hearing payload comes from, and how each source is reached.
     *
     * @param mode     the adapter serving the payload port
     * @param redis    the payload cache
     * @param fallback the query-side read used when the cache has nothing
     */
    public record Payload(
            @DefaultValue("LIVE") PayloadSourceMode mode,
            @DefaultValue Redis redis,
            @DefaultValue Fallback fallback) {
    }

    /**
     * The hearing payload cache.
     *
     * <p>The address is a LOCAL development default, matching the datasource convention above;
     * deployed environments override it, and the key is mounted from Key Vault. TLS is off by
     * default because that is what a developer's local server speaks, and on in every deployed
     * environment — with certificates verified, which is registered deviation 1.
     *
     * <p>The legacy {@code REDIS_MAX_RETRIES}, {@code REDIS_TOTAL_RETRY_TIME_IN_MS} and
     * {@code REDIS_NUMBER_OF_ATTEMPTS} variables have no counterpart here. They configured a retry
     * strategy the function app's client library never honoured (design defect D13), so porting them
     * would carry over settings that have never had an effect.
     *
     * @param host           cache host
     * @param port           cache port
     * @param password       cache access key; a secret, and therefore without a default
     * @param ssl            whether to connect over TLS, with certificates verified
     * @param keyPrefix      the payload prefix the producer writes under; {@code INT_} for this flow
     * @param connectTimeout how long to wait for a connection
     * @param commandTimeout how long to wait for a command to answer
     */
    public record Redis(
            @DefaultValue("localhost") String host,
            @DefaultValue("6379") int port,
            String password,
            @DefaultValue("false") boolean ssl,
            @DefaultValue("INT_") String keyPrefix,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("5s") Duration commandTimeout) {
    }

    /**
     * The query-side payload read.
     *
     * <p>The attempt count and interval are the function app's {@code DEFAULT_PUBLISH_RETRY_COUNT}
     * and {@code DEFAULT_PUBLISH_RETRY_INTERVAL} defaults, kept because the retry rule is ported
     * rather than redesigned.
     *
     * @param maxAttempts    total attempts including the first
     * @param retryInterval  the wait between attempts
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for a response
     */
    public record Fallback(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration retryInterval,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {
    }

    /**
     * The results context.
     *
     * <p>One base URL for both of its APIs, because they are one deployment behind one internal mesh
     * host; the context roots are part of each call's path and belong with the client that makes it.
     *
     * @param baseUrl the results context base URL; a LOCAL development default
     */
    public record Results(@DefaultValue("http://localhost:8080") String baseUrl) {
    }
}
