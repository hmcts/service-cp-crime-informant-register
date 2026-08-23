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
 * @param stub       test-only control over the stub adapters
 * @param payload    where the hearing payload is read from
 * @param results    the results context this service reads from and posts to
 * @param referencedata the reference-data context the register's recipients are looked up in
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
        @DefaultValue Referencedata referencedata) {

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
     * The results context: the query API the payload fallback reads from, and the command API this
     * service POSTs {@code add-informant-register} to.
     *
     * <p>One base URL for both of its APIs, because they are one deployment behind one internal mesh
     * host; the context roots are part of each call's path and belong with the client that makes it.
     * One identity for both, for the same reason — {@code CJSCPPUID} is what either API authorises
     * against.
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
     * The reference-data context: the query API the register's recipients are looked up in.
     *
     * <p>Its own block rather than a member of {@link Results}, because it is a different deployment
     * behind a different internal mesh host. The context root is part of the call's path and belongs
     * with the client that makes it, exactly as it does there.
     *
     * <p>{@code baseUrl} and {@code systemUserId} carry no default here for the same reasons they
     * carry none there: an endpoint a service invents is an endpoint it can talk to by mistake, and
     * the identity is a secret that arrives from Key Vault. The local development value in
     * {@code application.yaml} is the reference-data query API's own declared {@code baseUri}, and
     * the identity there falls back to the Results one — the function app threads a single
     * {@code cjscppuid} through both calls ({@code ReferenceDataService.js:44}), so an environment
     * that mounts one identity is not asked for a second.
     *
     * <p>{@code headers} exists for the reason it does on {@link Results}: {@code CJSCPPUID} is
     * documented and the authorisation scheme is not — reference data's own access-control rules
     * require the caller to be in a named user group — so whatever the mesh turns out to need can be
     * supplied without a code change and nothing is invented in the meantime.
     *
     * <p>The attempt count and interval are the function app's {@code DEFAULT_PUBLISH_RETRY_COUNT}
     * and {@code DEFAULT_PUBLISH_RETRY_INTERVAL} defaults ({@code AxiosRetryWrapper.js:10-11}),
     * because this call goes through the same wrapper the payload fallback does and the rule is
     * ported rather than redesigned.
     *
     * @param mode           the adapter serving the subscriptions port
     * @param baseUrl        scheme, host and port of the reference-data context, no path
     * @param systemUserId   the {@code CJSCPPUID} identity; a secret, never logged
     * @param headers        any further headers the mesh requires, name to value
     * @param maxAttempts    total attempts including the first
     * @param retryInterval  the wait between attempts
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for a response once connected
     */
    public record Referencedata(
            @DefaultValue("LIVE") SubscriptionsSourceMode mode,
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration retryInterval,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {

        /** Freezes the header map, and treats an unconfigured one as none rather than as absent. */
        public Referencedata {
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
}
