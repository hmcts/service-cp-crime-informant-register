package uk.gov.hmcts.cp.simulation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates {@code DistributionCommand} JSON messages matching the closed contract
 * ({@code distribution-command.schema.json}, {@code additionalProperties: false}).
 *
 * <p>Weighted production mix:
 * <ul>
 *   <li>60% — standard message (with userId)
 *   <li>20% — replay-shaped message (no userId, transition-window shape)
 *   <li>10% — duplicate of a previously published message (byte-identical replay)
 *   <li>10% — older hearing dates (7+ days ago)
 * </ul>
 *
 * <p>The 10% duplicate bucket maintains a ring buffer of recently published messages.
 * A duplicate returns the exact same message — same requestId, hearingId, and all fields — so the
 * service's {@code (source, requestId)} idempotency guard and (when the JMS MessageId is set)
 * the broker's duplicate-detection window both fire correctly.
 *
 * <p>The ring is seeded with messages that were never published, so an early "duplicate" can be a
 * first publish. The output verifier therefore derives uniqueness from the publish manifest's
 * hearingIds, never from which bucket produced a message.
 */
public final class MessageBuilder {

    private static final String SOURCE = "RESULTS";
    private static final int RING_SIZE = 50;
    private static final PublishedMessage[] RECENT = new PublishedMessage[RING_SIZE];
    private static final AtomicInteger RING_INDEX = new AtomicInteger(0);

    static {
        for (int i = 0; i < RING_SIZE; i++) {
            RECENT[i] = buildWithUserId(
                    UUID.randomUUID(), UUID.randomUUID(),
                    recentHearingDay(), UUID.randomUUID());
        }
    }

    private MessageBuilder() {
    }

    /**
     * Generates the next message. Thread-safe. The 10% duplicate bucket returns a previously
     * generated message unchanged, so its body is byte-identical.
     */
    public static PublishedMessage next() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 60) {
            return standard();
        } else if (roll < 80) {
            return replay();
        } else if (roll < 90) {
            return duplicate();
        } else {
            return olderHearing();
        }
    }

    /**
     * Returns the JMS MessageId for a given source and requestId, matching the broker's
     * duplicate-detection key format: {@code "{source}:{requestId}"}.
     *
     * <p>Gatling's JMS DSL does not expose {@code setJMSMessageID()}, so this helper cannot
     * be wired into the simulation's {@code jms().send()} chain. The service's
     * {@code (source, requestId)} idempotency guard is what exercises dedup in this test.
     * If Gatling adds a MessageId setter, wire it through the simulation's exec block.
     */
    public static String messageId(String source, UUID requestId) {
        return source + ":" + requestId;
    }

    private static PublishedMessage standard() {
        PublishedMessage message = buildWithUserId(
                UUID.randomUUID(), UUID.randomUUID(),
                recentHearingDay(), UUID.randomUUID());
        cache(message);
        return message;
    }

    private static PublishedMessage replay() {
        PublishedMessage message = buildWithoutUserId(
                UUID.randomUUID(), UUID.randomUUID(),
                recentHearingDay());
        cache(message);
        return message;
    }

    private static PublishedMessage duplicate() {
        return RECENT[ThreadLocalRandom.current().nextInt(RING_SIZE)];
    }

    private static PublishedMessage olderHearing() {
        LocalDate hearingDay = LocalDate.now(ZoneOffset.UTC)
                .minusDays(ThreadLocalRandom.current().nextInt(7, 30));
        PublishedMessage message = buildWithUserId(
                UUID.randomUUID(), UUID.randomUUID(),
                hearingDay, UUID.randomUUID());
        cache(message);
        return message;
    }

    private static void cache(PublishedMessage message) {
        int slot = Math.floorMod(RING_INDEX.getAndIncrement(), RING_SIZE);
        RECENT[slot] = message;
    }

    private static PublishedMessage buildWithUserId(UUID requestId, UUID hearingId,
                                                    LocalDate hearingDay, UUID userId) {
        Instant sharedTime = sharedTimeFor(hearingDay);
        return new PublishedMessage(requestId, hearingId, hearingDay, """
                {
                  "source": "%s",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "%s",
                  "sharedTime": "%s",
                  "eventType": "Hearing_Resulted",
                  "userId": "%s"
                }
                """.formatted(SOURCE, requestId, hearingId, hearingDay, sharedTime, userId));
    }

    private static PublishedMessage buildWithoutUserId(UUID requestId, UUID hearingId,
                                                       LocalDate hearingDay) {
        Instant sharedTime = sharedTimeFor(hearingDay);
        return new PublishedMessage(requestId, hearingId, hearingDay, """
                {
                  "source": "%s",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "%s",
                  "sharedTime": "%s",
                  "eventType": "Hearing_Resulted"
                }
                """.formatted(SOURCE, requestId, hearingId, hearingDay, sharedTime));
    }

    private static LocalDate recentHearingDay() {
        return LocalDate.now(ZoneOffset.UTC)
                .minusDays(ThreadLocalRandom.current().nextInt(0, 7));
    }

    private static Instant sharedTimeFor(LocalDate hearingDay) {
        int hour = ThreadLocalRandom.current().nextInt(8, 17);
        int minute = ThreadLocalRandom.current().nextInt(0, 60);
        return hearingDay.atTime(LocalTime.of(hour, minute)).toInstant(ZoneOffset.UTC);
    }
}
