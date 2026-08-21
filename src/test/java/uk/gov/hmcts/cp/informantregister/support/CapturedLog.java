package uk.gov.hmcts.cp.informantregister.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

/**
 * What one logger said, captured safely across threads.
 *
 * <p>The end-to-end suites read the log as evidence, and the lines they read are written by the
 * broker's callback threads while the test thread asserts on them. Logback's own
 * {@code ListAppender} is not built for that: its {@code list} is a plain {@code ArrayList}, so a
 * reader on another thread has no happens-before with the writer and may see a stale view — or fail
 * outright while a copy races an append. Where the log is the only evidence distinguishing two
 * behaviours, that is a suite that can pass for the wrong reason.
 *
 * <p>Two things make this one safe:
 *
 * <ul>
 *   <li>events land in a {@link CopyOnWriteArrayList}, so every append publishes and every snapshot
 *       is a consistent, immutable view;</li>
 *   <li>each event is <strong>prepared for deferred processing</strong> as it arrives. A logback
 *       event resolves its formatted message, its thread name and its <em>MDC map</em> lazily, and
 *       an event whose MDC is first resolved on the test thread reads the test thread's MDC —
 *       which is empty. Suites that filter by {@code requestId} would then find nothing, or, worse,
 *       find whatever the test thread happened to be carrying. Preparing on the appending thread
 *       pins the correlation identifiers to the delivery that produced them.</li>
 * </ul>
 *
 * <p>Closing detaches the appender, so one suite's capture never trails into the next.
 */
public final class CapturedLog implements AutoCloseable {

    private final Logger logger;
    private final CollectingAppender appender;

    private CapturedLog(final Logger logger, final CollectingAppender appender) {
        this.logger = logger;
        this.appender = appender;
    }

    /**
     * Starts capturing everything the given class logs.
     *
     * @param type the class whose logger to attach to
     * @return the capture, to be closed when the assertions are done
     */
    public static CapturedLog of(final Class<?> type) {
        final Logger logger = (Logger) LoggerFactory.getLogger(type);
        final CollectingAppender appender = new CollectingAppender();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLog(logger, appender);
    }

    /**
     * Every event captured so far, as an immutable snapshot.
     */
    public List<ILoggingEvent> events() {
        return List.copyOf(appender.events);
    }

    /**
     * Every captured line's formatted message, as an immutable snapshot.
     */
    public List<String> messages() {
        return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static final class CollectingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(final ILoggingEvent event) {
            // On the thread that logged it, while its MDC is still in place.
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }
}
