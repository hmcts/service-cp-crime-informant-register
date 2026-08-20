package uk.gov.hmcts.cp.informantregister.domain;

/**
 * The fingerprint of a request's immutable fields.
 *
 * <p>Written once when the processed-log row is created and never updated. A later delivery under
 * the same {@code (source, requestId)} whose fingerprint differs is an idempotency collision: the
 * producer has reused an identity for a different request, so the delivery is dead-lettered and the
 * existing row left untouched.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
        // Function holder.
    }

    /**
     * Returns the lowercase hex SHA-256 of the command's canonical immutable form.
     *
     * <p>{@code source} and {@code requestId} are deliberately absent: they are the key the
     * fingerprint is compared under, not part of what is being compared.
     */
    public static String of(final DistributionCommand command) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
