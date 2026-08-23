package uk.gov.hmcts.cp.informantregister.application;

import java.time.LocalDate;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.ReferenceDataUnavailableException;

/**
 * Where the now-subscriptions reference data a register is addressed with comes from.
 *
 * <p>The legacy fetches it inside the second activity
 * ({@code InformantRegisterSubscriptions/index.js:20} → {@code ReferenceDataService
 * .getSubscriptionsMetadata}). Here the fetch is a port and the matching is a pure function of what
 * it answers, so the transformation can be compared against the recorded goldens without a network.
 *
 * <p><strong>An answer with nothing in it is not a failure.</strong> Reference data may legitimately
 * answer with no body, a body with no {@code nowSubscriptions} member, or one with no
 * informant-register subscriptions among them; the legacy then returns the fragments untouched
 * ({@code index.js:22-33}) and the register is filed with no recipients. That is a
 * {@code null}-or-empty answer, not an exception.
 *
 * <p><strong>Not being able to ask is a failure, and it is transient.</strong> The legacy catches
 * every transport failure and returns {@code null} ({@code ReferenceDataService.js:52}), which is
 * indistinguishable on the wire from "nobody is subscribed" — so an outage silently ships a register
 * that reaches nobody. This port refuses instead; see {@code doc/DEVIATIONS.md} entry 14.
 *
 * <p><strong>Why the answer crosses as a tree and not as a record.</strong> This is the second
 * document that enters the core as {@code JsonNode}, and it is here for the reason Principle IV
 * gives for the first: it is owned elsewhere, sparsely populated, and nothing in it may be lost.
 * Three specific things forbid a typed model of it.
 *
 * <ul>
 *   <li>The matched subscriptions are not only <em>read</em> here — they are carried whole into the
 *       outbound mapping ({@code OutboundInformantRegister/index.js:44} →
 *       {@code RecipientMapper.js:15-22}), so a binding that dropped a member reference data added
 *       would drop a recipient's address out of a register. Unknown fields must survive untouched.
 *   <li>The matching rules read arbitrary depths of a subscription's own shape — nested child
 *       subscriptions, vocabulary flags, result and prompt lists ({@code SubscriptionsService.js
 *       :213,224,234,286,287}) — and reproduce the legacy's dereference of each element, including
 *       the {@code TypeError} a {@code null} element raises (registered deviation 7). A record tree
 *       cannot express "this element was JSON null and reading it must fail".
 *   <li>The shape is reference data's contract, not this service's. Principle IV requires typed
 *       records for what this service <em>produces</em>; this is something it consumes.
 * </ul>
 *
 * <p>What the adapter still owes, and pays, is the difference between an answer and no answer:
 * anything but a 2xx carrying readable JSON is reported rather than returned. Shape is the matching
 * step's business, because in this flow shape is a business outcome.
 */
public interface NowSubscriptionsSource {

    /**
     * Fetches the now-subscriptions body for a given day.
     *
     * <p>The identity travels with the call because reference data authorises on it and the legacy
     * makes this call as the user who shared the results
     * ({@code ReferenceDataService.js:44}, whose {@code input.cjscppuid} is the orchestration input
     * threaded from the trigger). It is the run's identity, not the adapter's, so it is a parameter
     * rather than something the adapter holds — an adapter that resolved it for itself could make
     * this call as one user and the POST as another.
     *
     * @param on       the day the query is dated with — {@code ReferenceDataService.js:38} derives it
     *                 from the register date as
     *                 {@code new Date(registerDate).toISOString().slice(0, 10)}
     * @param identity who the read is made as; the adapter falls back to its configured system
     *                 identity when the run names no user
     * @return the now-subscriptions body, or {@code null} when reference data answered with none
     * @throws ReferenceDataUnavailableException if reference data could not be asked
     */
    JsonNode fetch(LocalDate on, CallerIdentity identity);
}
