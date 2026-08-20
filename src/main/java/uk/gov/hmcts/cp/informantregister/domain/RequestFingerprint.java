package uk.gov.hmcts.cp.informantregister.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The fingerprint of a request's immutable fields.
 *
 * <p>Written once when the processed-log row is created and never updated. A later delivery under
 * the same {@code (source, requestId)} whose fingerprint differs is an idempotency collision: the
 * producer has reused an identity for a different request, so the delivery is dead-lettered and the
 * existing row left untouched.
 */
public final class RequestFingerprint {

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final char SEPARATOR = '|';

    private RequestFingerprint() {
        // Function holder.
    }

    /**
     * Returns the lowercase hex SHA-256 of the command's canonical immutable form.
     *
     * <p>{@code source} and {@code requestId} are deliberately absent: they are the key the
     * fingerprint is compared under, not part of what is being compared.
     */
    // PMD.ShortMethodName: `of` is the platform's own name for a static factory (List.of,
    // Optional.of, HexFormat.of). Lengthening it here would read worse at every call site.
    @SuppressWarnings("PMD.ShortMethodName")
    public static String of(final DistributionCommand command) {
        return HexFormat.of().formatHex(digestOf(canonicalFormOf(command)));
    }

    /**
     * The canonical form every component is normalised into before hashing.
     *
     * <p>Each component is taken from the parsed value rather than from the wire text, which is what
     * makes an uppercase-hex identifier and an offset-bearing instant hash identically to their
     * lowercase and {@code Z} equivalents. {@code Instant.toString()} is the normal form for the
     * shared time: always UTC, and with a fraction only when there is one to show.
     */
    private static String canonicalFormOf(final DistributionCommand command) {
        return new StringBuilder()
                // Redundant against UUID.toString(), which is specified to emit lower case, but
                // kept so this method reads as the data model writes it. The case normalisation
                // that actually does work happens at parse time, and is pinned there.
                .append(command.hearingId().toString().toLowerCase(Locale.ROOT))
                .append(SEPARATOR)
                .append(command.hearingDay().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .append(SEPARATOR)
                .append(command.sharedTime().toString())
                .append(SEPARATOR)
                .append(command.eventType())
                .toString();
    }

    private static byte[] digestOf(final String canonicalForm) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM)
                    .digest(canonicalForm.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java platform, so this cannot happen on a running JVM;
            // if it ever did, no request could be recorded safely and failing loudly is the only
            // honest response.
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", unavailable);
        }
    }
}
