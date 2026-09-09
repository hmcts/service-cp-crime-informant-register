package uk.gov.hmcts.cp.informantregister.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a run's outbound calls are made as.
 *
 * <p>The legacy answers this once per run and never again: the hearing-resulted envelope's
 * {@code userId} becomes the orchestration's {@code cjscppuid}
 * ({@code InformantRegisterEventGridTrigger/index.js:15}) and
 * {@code InformantRegisterOrchestrator/index.js:13,31,46} threads that one value into the payload
 * read, the now-subscriptions read ({@code ReferenceDataService.js:44}) and the
 * {@code add-informant-register} POST ({@code ProcessOutboundInformantRegister/index.js:21}). One
 * identity, three calls, from the user who shared the results. This type is that answer, made
 * explicit so it cannot be resolved three times and come out three ways.
 *
 * <p><strong>Having no user is a state, not a gap.</strong> A producer build from before
 * {@code userId} was agreed sends none, and so does a replay that does not carry the original body —
 * one rebuilt by hand, or one re-sent deliberately without the field because the original user has
 * been deactivated. Those runs are made under the configured system identity, which is what
 * {@link #orSystem(String)} is for: the per-request user takes precedence where there is one, and
 * the configured identity is the fallback rather than the other way round. A replay that carries the
 * original body carries the original {@code userId} with it, and is attributed to that user like any
 * other delivery; see {@code doc/DEVIATIONS.md} #16.
 *
 * <p><strong>It is never logged.</strong> A user identifier is PII-adjacent and the configured
 * identity is a secret; both leave this service in a {@code CJSCPPUID} header and nowhere else.
 *
 * <p>Which of the two answers a run got <em>is</em> written down, on the receipt line, as the
 * bounded label {@link #label()} produces. That is deliberately a different fact from the one above:
 * an attribution complaint is either a producer that named no user or this type's documented
 * fallback behaving as designed, and no other field on the line separates them. Which identity,
 * never whose — and {@code TelemetryPrivacyTest} holds the delivery path to it. MDC carries
 * {@code requestId}, {@code hearingId}, {@code hearingDay} and {@code source}, plus what the broker
 * stamped on the delivery; it does not carry this.
 *
 * @param userId the user the run is attributed to, where the message named one
 */
public record CallerIdentity(Optional<UUID> userId) {

    /**
     * A run no user is named for: a message published before the field existed, or a replay that
     * does not carry the original body.
     */
    public static final CallerIdentity SYSTEM = new CallerIdentity(Optional.empty());

    /**
     * Refuses a {@code null}, which would be a second spelling of {@link #SYSTEM}.
     */
    public CallerIdentity {
        Objects.requireNonNull(userId, "userId is Optional.empty() when absent, never null");
    }

    /**
     * The identity the command was published under.
     *
     * @param command the validated request
     * @return the user it named, or {@link #SYSTEM} where it named none
     */
    // PMD.ShortMethodName: `of` is the platform's own name for a static factory, and it is the name
    // RequestFingerprint already uses for the same shape of call on the same argument.
    @SuppressWarnings("PMD.ShortMethodName")
    public static CallerIdentity of(final DistributionCommand command) {
        return command.userId().isEmpty() ? SYSTEM : new CallerIdentity(command.userId());
    }

    /**
     * The value a {@code CJSCPPUID} header carries for this run.
     *
     * <p>The configured identity is passed in rather than held here because each client authorises
     * against its own: {@code informantregister.results.system-user-id} for the two Results calls,
     * {@code informantregister.referencedata.system-user-id} for reference data. What must not vary
     * between them is the per-request user, and it does not — it is this record, resolved from one
     * command.
     *
     * @param systemUserId the identity configured for the client making the call
     * @return the run's user where it has one, and the configured identity otherwise
     */
    public String orSystem(final String systemUserId) {
        return userId.map(UUID::toString).orElse(systemUserId);
    }

    /**
     * Which of the two identities this run is made as, as a bounded token for the log.
     *
     * <p>It lives here, beside {@link #orSystem(String)}, because it is the same question and must
     * not become a second answer to it. The transport adapter derived it independently at first —
     * truthfully, since the two conditions were exact negations — but the receipt line is the one
     * field claiming to say which identity the outbound calls went out as, and a duplicated rule is
     * how that claim quietly stops being true. Give this type any further condition, a nil-uuid
     * guard or a deactivated-user rule, and one resolution now produces both the header and the
     * token; a line that read {@code message-user} while {@code CJSCPPUID} carried the configured
     * identity would be worse than no line, because settling attribution complaints is its whole
     * purpose.
     *
     * <p>A bounded pair of words rather than a boolean, because a reader searching a log index for
     * an attribution problem searches for a state rather than decoding a flag — and because carrying
     * no user is a state this contract accepts rather than a defect ({@link #SYSTEM}).
     *
     * @return {@code message-user} where the message named one, {@code system-identity} otherwise
     */
    public String label() {
        return userId.isPresent() ? "message-user" : "system-identity";
    }
}
