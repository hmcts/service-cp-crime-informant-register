package uk.gov.hmcts.cp.informantregister.adapter.results;

import java.util.UUID;

/**
 * What a line about one outbound command can be traced back to.
 *
 * <p>It reaches no header, no URL and no body. The gateway takes it so that its own lines can say
 * which register they are about, which is the difference between an actionable line and a line that
 * only reports that something is wrong.
 *
 * <p><strong>Why the identifiers travel as an argument at all.</strong> They already reach the log
 * index through the MDC the message listener puts in place: the run is a single thread from the
 * broker's callback down to this class — a sequential loop over the authorities and a blocking
 * {@code RestClient} — so every line here inherits {@code requestId} and {@code hearingId} today
 * without being told them. That is a property of how the pipeline currently submits, not a property
 * of this adapter. Parallelising the per-authority POSTs is the obvious optimisation and would strip
 * the MDC from every line in this package silently, with no test failing; carrying the identifiers
 * explicitly makes the adapter's lines self-describing whatever thread they end up on, and it puts
 * this class in line with the rest of the service, where {@code DistributionPipeline},
 * {@code IdempotencyGuard} and {@code ResultsRegisterSubmissionClient} all name the request in the
 * text as well as in the MDC.
 *
 * <p>The authority is here rather than beside it because a hearing produces one command per
 * prosecuting authority: without it a run's five lines are indistinguishable, and with it alone
 * nothing says which hearing lost a register.
 *
 * <p>Everything on it is an identifier. There is no defendant data, no free text a server chose and
 * nothing a producer wrote, so the whole record is admissible at {@code info} (constitution
 * Principle VII).
 *
 * @param source      the publishing system, which namespaces the request id
 * @param requestId   the request this command belongs to
 * @param hearingId   the hearing whose register this command files
 * @param authorityId the prosecuting authority this command addresses
 */
public record CommandCorrelation(
        String source, UUID requestId, UUID hearingId, String authorityId) {

    /**
     * The record as one run of {@code key=value} pairs, for a log line's message.
     *
     * <p>Rendered here rather than at each call site so that six log statements cannot each spell
     * the same four fields slightly differently — a log index is only searchable if the key is the
     * same every time. Deliberately not {@code toString()}: a record whose {@code toString} is a log
     * fragment is a trap for the next reader who embeds it somewhere else.
     *
     * @return {@code source=… requestId=… hearingId=… authority=…}
     */
    public String logFields() {
        return "source=" + source + " requestId=" + requestId
                + " hearingId=" + hearingId + " authority=" + authorityId;
    }
}
