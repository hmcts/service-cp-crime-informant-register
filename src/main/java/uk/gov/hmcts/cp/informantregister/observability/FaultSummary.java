package uk.gov.hmcts.cp.informantregister.observability;

import java.sql.SQLException;
import java.util.StringJoiner;
import java.util.function.Predicate;

/**
 * What a failure is allowed to say about itself in a log line.
 *
 * <p>This service reports failures by bounded, code-owned fact and never by the words a library
 * chose (constitution Principle VII, spec FR-012). The reason is not squeamishness: a Redis
 * exception routinely quotes the key it was asked for and, on a parse failure, the bytes it choked
 * on — which is the hearing payload, arriving by the back door; an AMQP exception quotes the
 * broker; a driver's exception can quote a connection string. A stack trace reaches a log index
 * exactly as a message does.
 *
 * <p>But "report the exception's class name and nothing else" — which is what this service did
 * before — throws away the part support actually needs. A bare
 * {@code type=com.azure.messaging.servicebus.ServiceBusException} says only that Azure was
 * involved. The fault's <em>shape</em> is diagnostic and is entirely ours to publish: class names
 * are written by whoever wrote the code, not by whoever sent the message, and a {@code SQLState} is
 * a five-character code from a standard.
 *
 * <p>So every method here renders structure and never text. Nothing in this class calls
 * {@link Throwable#getMessage()}, and {@code FaultSummaryTest} exists to keep it that way.
 *
 * <p>Every walk is bounded. The chains come from libraries, and a self-referential one must
 * terminate rather than hang a settlement path.
 */
public final class FaultSummary {

    /**
     * How deep to walk a cause chain before giving up.
     *
     * <p>The single bound for every traversal in this service — the listener's lock-loss
     * classification walks through {@link #anyCause} so that this is the only place the limit is
     * stated.
     */
    public static final int MAX_CAUSE_DEPTH = 10;

    /** Rendered where a failure, a cause, or a code is absent — never an empty string or "null". */
    private static final String ABSENT = "none";

    private static final String LINK = "<-";

    private static final String TRUNCATED = "...";

    private FaultSummary() {
    }

    /**
     * The failure and its causes, by type.
     *
     * <p>The head is fully qualified because which library produced it is the first question;
     * the causes beneath it are simple names, because by then the package is established and the
     * line still has to be readable. A chain longer than {@link #MAX_CAUSE_DEPTH} ends in
     * {@code <-...} so a truncated rendering cannot be mistaken for a complete one.
     *
     * @param failure the failure to describe; may be {@code null}
     * @return e.g. {@code com.azure...ServiceBusException<-AmqpException<-SocketTimeoutException},
     *     or {@code none}
     */
    public static String typeChain(final Throwable failure) {
        final String rendered;
        if (failure == null) {
            rendered = ABSENT;
        } else {
            final StringJoiner chain = new StringJoiner(LINK);
            chain.add(failure.getClass().getName());

            Throwable current = failure.getCause();
            int depth = 1;
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                chain.add(current.getClass().getSimpleName());
                current = current.getCause();
                depth++;
            }
            if (current != null) {
                chain.add(TRUNCATED);
            }
            rendered = chain.toString();
        }
        return rendered;
    }

    /**
     * The state and vendor code of the first database failure in the chain.
     *
     * <p>Both halves are what a support engineer needs to tell a unique-constraint violation from a
     * connection refusal from a permission problem, and neither is anybody's free text. The state
     * is reported as {@code none} when the driver did not set one, so the line still reads
     * honestly.
     *
     * @param failure the failure to inspect; may be {@code null}
     * @return {@code state/code}, or {@code none} when nothing in the chain is a database failure
     */
    public static String sqlState(final Throwable failure) {
        String rendered = ABSENT;
        Throwable current = failure;
        boolean found = false;
        for (int depth = 0; !found && current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException database) {
                final String state = database.getSQLState();
                rendered = (state == null ? ABSENT : state) + '/' + database.getErrorCode();
                found = true;
            }
            current = current.getCause();
        }
        return rendered;
    }

    /**
     * Whether anything in the chain satisfies a test.
     *
     * <p>The shared bounded traversal. Callers that need to classify against an infrastructure type
     * — the listener's lock-loss test, for instance — supply the predicate and keep the
     * infrastructure import to themselves, so this class stays usable from every layer.
     *
     * @param failure the failure to inspect; may be {@code null}
     * @param wanted  the test to apply to the failure and each of its causes
     * @return {@code true} if the failure or a cause within the depth bound satisfies {@code wanted}
     */
    public static boolean anyCause(final Throwable failure, final Predicate<Throwable> wanted) {
        boolean matched = false;
        Throwable current = failure;
        for (int depth = 0; !matched && current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            matched = wanted.test(current);
            current = current.getCause();
        }
        return matched;
    }
}
