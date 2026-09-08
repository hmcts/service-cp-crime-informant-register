package uk.gov.hmcts.cp.informantregister.pipeline;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.informantregister.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.informantregister.application.RegisterTransformer;
import uk.gov.hmcts.cp.informantregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.informantregister.domain.InformantRegisterDocument;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragment;
import uk.gov.hmcts.cp.informantregister.domain.RegisterFragmentWithSubscriptions;
import uk.gov.hmcts.cp.informantregister.domain.TransformationFailedException;

/**
 * The three ported activities, chained exactly as {@code InformantRegisterOrchestrator} chains them.
 *
 * <pre>
 *   SetInformantRegister  ->  InformantRegisterSubscriptions  ->  OutboundInformantRegister
 *   (RegisterBuilder)         (SubscriptionMatcher)               (AggregationMapper)
 * </pre>
 *
 * <p>The orchestrator is only twenty lines and every one of them is load-bearing, so each is named
 * below against the line that produced it ({@code InformantRegisterOrchestrator/index.js:21-41}).
 *
 * <p><strong>The activity boundary is a real boundary, and that makes the immutable tree correct.</strong>
 * The orchestrator holds one hearing object and hands it to two activities — {@code index.js:22} and
 * {@code index.js:39}. {@code callActivity} is not a function call: the Durable Task extension
 * serialises the input, persists it in the orchestration history, and the activity runs against its
 * own deserialised copy. So the writes {@code DefendantContextBaseService} and
 * {@code RegisterFragmentService} make onto judicial-result nodes land on a copy, and
 * {@code OutboundInformantRegister} receives the orchestrator's untouched original. The parity pack
 * checked that rather than assuming it — {@code node oracle/verify-boundary.js} runs all 384 cases
 * both ways and reports 0 changing — and the consequence is that this port's immutable-{@code JsonNode}
 * stance <em>matches</em> Node here rather than diverging from it. The mutated values still reach the
 * document, because they travel in the <em>fragment</em>, which is what {@link RegisterBuilder}
 * returns.
 *
 * <p><strong>The reference-data call is split in two, and the split is where the legacy's own split
 * is.</strong> {@code ReferenceDataService.getSubscriptionsMetadata} computes its {@code on} query
 * parameter at {@code :38} — <em>outside</em> the try block that starts at {@code :41} — and only then
 * makes the call. So a register date {@code new Date()} cannot read is a {@code RangeError} that
 * escapes the service entirely, while a transport failure is caught and answered with {@code null}.
 * The first belongs to the transformation and is reproduced here; the second belongs to the adapter
 * behind {@link NowSubscriptionsSource}, which refuses rather than answering {@code null}
 * ({@code doc/DEVIATIONS.md} entry 14).
 */
// PMD.OnlyOneReturn: the early returns mirror the legacy source's own, line for line —
// funnelling them through a single exit would reshape the very control flow the parity
// harness pins (constitution Principle I, bug-for-bug parity).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class RegisterTransformationChain implements RegisterTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterTransformationChain.class);

    private final RegisterBuilder builder;
    private final SubscriptionMatcher matcher;
    private final AggregationMapper aggregation;
    private final NowSubscriptionsSource subscriptions;

    /**
     * Creates the chain over the three steps and the reference-data port.
     *
     * @param builder       the {@code SetInformantRegister} port
     * @param matcher       the {@code InformantRegisterSubscriptions} port
     * @param aggregation   the {@code OutboundInformantRegister} port
     * @param subscriptions where the now-subscriptions body comes from
     */
    public RegisterTransformationChain(
            final RegisterBuilder builder,
            final SubscriptionMatcher matcher,
            final AggregationMapper aggregation,
            final NowSubscriptionsSource subscriptions) {

        this.builder = builder;
        this.matcher = matcher;
        this.aggregation = aggregation;
        this.subscriptions = subscriptions;
    }

    /** {@inheritDoc} */
    @Override
    public List<InformantRegisterDocument> transform(
            final JsonNode hearing, final String sharedTime, final CallerIdentity identity) {

        // index.js:21-24 — SetInformantRegister.
        final List<RegisterFragment> fragments = builder.build(hearing, sharedTime);

        // index.js:27 — `if (informantRegisters)`. A falsy result stops the chain: no subscription
        // matching, no outbound mapping, no POST. An empty list is this port's answer where the
        // legacy answers `undefined` (deviations-register entry 6), and it stops the chain here in
        // exactly the same way.
        if (fragments.isEmpty()) {
            // `path` rather than Json.array: a log argument must not be able to throw, and
            // Json.array refuses a truthy non-array. `path` counts a non-array as zero, which is
            // the honest answer to "how many defendants did the builder have to work with".
            LOG.info("Hearing produced no register fragments; nothing is addressed or sent. "
                    + "defendants={}", hearing.path("defendants").size());
            return List.of();
        }

        // index.js:29-33 — InformantRegisterSubscriptions, whose first act is to find the register
        // date (index.js:17-18) and whose second is to fetch reference data dated with it.
        final JsonNode answer =
                subscriptions.fetch(queryDate(matcher.registerDate(fragments)), identity);
        final List<RegisterFragmentWithSubscriptions> addressed = matcher.match(fragments, answer);

        // "Authority X got no register" has three different causes with three different owners: no
        // fragment was built for it (this service), reference data returned no subscriptions at all
        // (reference data), or its subscriptions did not match the hearing (a business
        // configuration question). The per-authority detail stays at DEBUG in the matcher; this is
        // the line that says which of the three it was without turning tracing on.
        final long unaddressed = addressed.stream()
                .filter(fragment -> matchedSubscriptions(fragment).isEmpty())
                .count();
        LOG.info("Register fragments addressed. authorities={} authoritiesWithNoSubscription={}",
                addressed.size(), unaddressed);

        // index.js:37-41 — OutboundInformantRegister, over the ORIGINAL hearing.
        final List<InformantRegisterDocument> documents = new ArrayList<>(addressed.size());
        for (int index = 0; index < addressed.size(); index++) {
            documents.add(aggregation.build(
                    hearing, fragments.get(index), matchedSubscriptions(addressed.get(index))));
        }
        LOG.info("Hearing transformed into {} register document(s). fragments={}",
                documents.size(), fragments.size());
        return List.copyOf(documents);
    }

    /**
     * The subscriptions one authority matched, as the aggregation reads them.
     *
     * <p>{@code OutboundInformantRegister/index.js:44} passes
     * {@code informantRegister.matchedSubscriptions || []}, so a fragment that never went through
     * matching maps exactly like one that matched nothing. The two are still different <em>fragments</em>
     * — one carries no member at all — but they are the same input to the recipient mapping.
     *
     * @param addressed the fragment as the matching step left it
     * @return the matched subscriptions, never {@code null}
     */
    private static List<JsonNode> matchedSubscriptions(
            final RegisterFragmentWithSubscriptions addressed) {

        return addressed.matchedSubscriptions() == null
                ? List.of()
                : addressed.matchedSubscriptions();
    }

    /**
     * The day the now-subscriptions query is dated with.
     *
     * <p>Ports {@code ReferenceDataService.js:38}: {@code new Date(on).toISOString().slice(0, 10)}.
     * Two details are carried rather than improved.
     *
     * <p>The first is that this throws. The expression sits outside the service's own try block, so a
     * register date {@code new Date()} cannot read raises a {@code RangeError} that escapes into
     * {@code InformantRegisterSubscriptions/index.js:89}, which swallows it and returns
     * {@code undefined}; {@code OutboundInformantRegister/index.js:17} then dereferences
     * {@code .length} on that, throws, and swallows in turn. The hearing produces nothing and nothing
     * records why. Here it is a classified, recorded refusal — deviations-register entry 7 — and the
     * documents produced are the same as the legacy's: none.
     *
     * <p>The second is <em>which</em> day it lands on. The register date is a London wall-clock time
     * carrying a literal {@code Z} (defect D9), so {@code new Date} reads it as UTC and the day is the
     * UTC day of that misleading instant. On a British Summer Time evening that is the following day.
     * Reading the register date as London local would be the natural correction and would change the
     * reference-data the register is addressed with, so it is not made here.
     *
     * @param registerDate the register date the fragments carry
     * @return the day to date the query with
     * @throws TransformationFailedException if the register date cannot be read as an instant
     */
    private static LocalDate queryDate(final String registerDate) {
        try {
            return OffsetDateTime.parse(registerDate)
                    .atZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDate();
        } catch (DateTimeException notAnInstant) {
            // The register date is derived from producer-supplied text, so it is never quoted back.
            throw new TransformationFailedException(
                    "the register date cannot date the now-subscriptions query");
        }
    }
}
