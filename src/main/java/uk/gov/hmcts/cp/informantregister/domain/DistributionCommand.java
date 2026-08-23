package uk.gov.hmcts.cp.informantregister.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The validated form of an inbound queue message.
 *
 * <p>A typed record rather than a JSON tree because this is the service's own closed contract, not a
 * foreign payload: the fields are agreed jointly with the publishing context and the schema is
 * {@code additionalProperties: false}. Inbound hearing payloads — which this service does not own —
 * stay canonical trees; see the constitution's Principle IV.
 *
 * <p><strong>Six of the seven are required; {@code userId} is not.</strong> It names the user whose
 * share of the results produced this message, and every downstream call of the run is attributed to
 * them — which is what the function app does with the envelope's {@code userId}
 * ({@code InformantRegisterEventGridTrigger/index.js:15} → {@code cjscppuid}). It is optional
 * because two legitimate producers have no user to name: support replay tooling, where the replay is
 * built by hand rather than re-sent verbatim or is re-sent deliberately without the field because
 * the original user has been deactivated, and any producer build from before the field existed.
 * Those messages run under the configured system identity instead, which is why the absence is
 * modelled as an empty {@link Optional} rather than as a defect. A replay that <em>is</em> re-sent
 * verbatim carries the original {@code userId} and is attributed to that user like any other
 * delivery; see {@code doc/DEVIATIONS.md} #16.
 *
 * <p>Every component is normalised at parse time, so an uppercase-hex identifier and an
 * offset-bearing instant arrive here in the same canonical shape as their lowercase and {@code Z}
 * equivalents.
 *
 * <p>Canonical schema: {@code src/main/resources/contracts/distribution-command.schema.json}.
 *
 * @param source      publishing system; namespaces {@code requestId} in the idempotency key
 * @param requestId   publisher-minted, deterministic from the hearing, day and shared time
 * @param hearingId   the resulted hearing
 * @param hearingDay  the hearing day this share relates to
 * @param sharedTime  the instant at which the hearing was shared, normalised to UTC
 * @param eventType   the publishing event; {@code Hearing_Resulted} only
 * @param userId      the user who shared the results, where the message named one; empty for a
 *                    transition-window message, or a replay that does not carry the original body,
 *                    both of which run under the system identity
 */
public record DistributionCommand(
        String source,
        UUID requestId,
        UUID hearingId,
        LocalDate hearingDay,
        Instant sharedTime,
        String eventType,
        Optional<UUID> userId) {

    /**
     * Refuses a {@code null} where the contract has a presence-or-absence answer.
     *
     * <p>{@code null} and {@link Optional#empty()} would both mean "no user" to every reader of this
     * record, and having two spellings of one state is how one of them ends up unhandled at a call
     * site. There is one.
     */
    public DistributionCommand {
        Objects.requireNonNull(userId, "userId is Optional.empty() when absent, never null");
    }

    /**
     * A command carrying no user, which is a message this contract accepts rather than a shortcut.
     *
     * <p>It is the shape a replay built without the original body carries, and the shape every
     * producer sent before {@code userId} was agreed, so it deserves to be constructible without
     * spelling out an absence.
     *
     * @param source     publishing system
     * @param requestId  publisher-minted request identity
     * @param hearingId  the resulted hearing
     * @param hearingDay the hearing day this share relates to
     * @param sharedTime the instant at which the hearing was shared
     * @param eventType  the publishing event
     */
    public DistributionCommand(
            final String source,
            final UUID requestId,
            final UUID hearingId,
            final LocalDate hearingDay,
            final Instant sharedTime,
            final String eventType) {
        this(source, requestId, hearingId, hearingDay, sharedTime, eventType, Optional.empty());
    }
}
