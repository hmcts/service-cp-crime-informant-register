package uk.gov.hmcts.cp.informantregister.config;

import java.time.Duration;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the cache connection is actually made with.
 *
 * <p>One claim here, and it is registered deviation 1: where TLS is used, the certificate is
 * verified. The function app connects with {@code rejectUnauthorized: false} and this deliberately
 * does not, which was signed off at ratification as a security fix that changes nothing a register
 * contains.
 *
 * <p>A deviation the register names and nothing asserts is a deviation that reverts the next time
 * somebody meets a self-signed certificate in a test environment and reaches for the setting that
 * makes the error go away. The Redis suite cannot prove it — a container with a self-signed
 * certificate would only show that a test can be told to trust one — so it is proven here, on the
 * settings the client is built from.
 */
@DisplayName("Live payload configuration")
class LivePayloadConfigTest {

    private static InformantRegisterProperties.Redis redis(final boolean ssl) {
        return new InformantRegisterProperties.Redis(
                "cache.internal", 6380, "a-key", ssl, "INT_",
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Nested
    @DisplayName("transport security — registered deviation 1")
    class TransportSecurity {

        @Test
        void the_cache_connection_should_verify_the_certificate_when_tls_is_used() {
            final RedisURI uri = LivePayloadConfig.cacheUri(redis(true));

            assertThat(uri.isSsl()).isTrue();
            assertThat(uri.isVerifyPeer())
                    .as("registered deviation 1: legacy disables certificate checks, this does not")
                    .isTrue();
        }

        /**
         * A developer's local server speaks plain TCP, so peer verification has nothing to verify.
         * The setting follows TLS rather than standing on its own, which is what keeps every
         * deployed environment — all of which use TLS — verified.
         */
        @Test
        void the_cache_connection_should_not_ask_for_verification_it_cannot_perform() {
            final RedisURI uri = LivePayloadConfig.cacheUri(redis(false));

            assertThat(uri.isSsl()).isFalse();
            assertThat(uri.isVerifyPeer()).isFalse();
        }
    }

    @Nested
    @DisplayName("the address")
    class Address {

        @Test
        void the_cache_connection_should_carry_the_configured_address_and_timeout() {
            final RedisURI uri = LivePayloadConfig.cacheUri(redis(true));

            assertThat(uri.getHost()).isEqualTo("cache.internal");
            assertThat(uri.getPort()).isEqualTo(6380);
            assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(5));
        }

        /**
         * A local server has no password, and an empty one is not a credential — sending it would
         * fail the handshake against a server that expects none.
         */
        @Test
        void the_cache_connection_should_carry_no_credential_when_none_is_configured() {
            final RedisURI uri = LivePayloadConfig.cacheUri(new InformantRegisterProperties.Redis(
                    "localhost", 6379, "  ", false, "INT_",
                    Duration.ofSeconds(5), Duration.ofSeconds(5)));

            assertThat(uri.getCredentialsProvider().resolveCredentials().block().hasPassword())
                    .isFalse();
        }
    }
}
