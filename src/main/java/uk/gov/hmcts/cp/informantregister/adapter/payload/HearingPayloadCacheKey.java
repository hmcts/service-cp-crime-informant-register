package uk.gov.hmcts.cp.informantregister.adapter.payload;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The cache key the hearing payload is published under.
 *
 * <p>A direct port of the function app's {@code getCacheKey}
 * ({@code HearingResultedCacheQuery/index.js}): the prefix, the hearing identifier, the hearing day
 * when there is one, and the literal {@code _result_} suffix. The producer writes the key; this
 * service only reads it, so the shape is not this service's to improve.
 *
 * <p>Two forms exist because the producer publishes two. The dated form is what a current share
 * writes; the undated form is its legacy twin, kept because payloads written under it are still
 * readable (design doc §2.1, {@code doc/API_CONTRACTS.md} field semantics for {@code hearingDay}).
 */
public final class HearingPayloadCacheKey {

    private static final String SUFFIX = "_result_";

    private HearingPayloadCacheKey() {
        // Key construction only.
    }

    /**
     * Builds the key for a hearing, with the hearing day when one is supplied.
     *
     * @param prefix     the payload prefix the producer writes under, {@code INT_} for this flow
     * @param hearingId  the hearing the payload belongs to
     * @param hearingDay the hearing day, or {@code null} for the legacy undated form
     * @return the key to read
     */
    public static String cacheKey(final String prefix, final UUID hearingId,
            final LocalDate hearingDay) {
        final String day = hearingDay == null ? "" : "_" + hearingDay;
        return prefix + hearingId + day + SUFFIX;
    }
}
