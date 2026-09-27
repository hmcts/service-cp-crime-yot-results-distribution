package uk.gov.hmcts.cp.yotresultsdistribution.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /**
     * The logger whose level was lowered, held by name rather than by reference.
     *
     * <p>By name because a second field of logger type is a class with two loggers as far as static
     * analysis is concerned, and the rule that says so is worth keeping for the classes it is
     * actually about. Re-resolving by name at close is exact: logback interns one logger per name.
     */
    private final String loweredName;

    private final Level levelToRestore;

    private CapturedLog(
            final Logger logger,
            final CollectingAppender appender,
            final String loweredName,
            final Level levelToRestore) {
        this.logger = logger;
        this.appender = appender;
        this.loweredName = loweredName;
        this.levelToRestore = levelToRestore;
    }

    /**
     * Starts capturing everything the given class logs.
     *
     * @param type the class whose logger to attach to
     * @return the capture, to be closed when the assertions are done
     */
    public static CapturedLog capturing(final Class<?> type) {
        return attachTo((Logger) LoggerFactory.getLogger(type), null, null);
    }

    /**
     * Starts capturing everything logged under the given logger name, descendants included.
     *
     * <p>For suites whose claim is about the service as a whole — "every failure path emits exactly
     * one ERROR" is a statement about all of them at once, and naming the classes would quietly turn
     * it into a statement about the ones somebody remembered. Attaching at the package keeps a new
     * component inside the claim from the moment it is written.
     *
     * @param loggerName the logger to attach to, typically a package
     */
    public static CapturedLog capturing(final String loggerName) {
        return attachTo((Logger) LoggerFactory.getLogger(loggerName), null, null);
    }

    /**
     * Starts capturing everything <em>anything</em> logs, down to TRACE.
     *
     * <p>For the privacy suite, whose claim is about what is written at any level by any component —
     * a claim a per-class capture cannot make, and one the deployed INFO threshold would hide rather
     * than disprove. The root level is restored when the capture closes.
     */
    public static CapturedLog everything() {
        final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        return attachTo(root, root, Level.TRACE);
    }

    /**
     * Starts capturing every line <em>anything</em> writes at the level a deployed pod writes at,
     * and everything one named logger writes down to TRACE.
     *
     * <p>{@link #everything()}'s reading of "any level" is the right one where nothing else is
     * running. It is the wrong one where real infrastructure is: an HTTP client, a cache client and
     * a broker client all log the bytes they carried at TRACE — that is what TRACE is for — so a
     * suite that turned TRACE on across a live stack would be asserting that third-party libraries
     * do not do the thing they are designed to do, and would fail without this service having
     * written anything at all. The shipped configuration puts the root at INFO and
     * {@code TelemetryPrivacyTest} refuses any level below it, so those lines cannot exist in a
     * deployed pod.
     *
     * <p>What this captures is therefore exactly what a deployed pod can emit — every logger at the
     * shipped threshold — plus every level of the one package whose lines this repository writes and
     * could be asked to turn up for a local diagnosis.
     *
     * @param tracedLoggerName the logger, typically this service's package root, to capture down to
     *                         TRACE
     * @return the capture, to be closed when the assertions are done
     */
    public static CapturedLog everythingAndAllOf(final String tracedLoggerName) {
        return attachTo(
                (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME),
                (Logger) LoggerFactory.getLogger(tracedLoggerName),
                Level.TRACE);
    }

    private static CapturedLog attachTo(
            final Logger logger, final Logger toLower, final Level loweredTo) {
        final CollectingAppender appender = new CollectingAppender();
        appender.start();
        logger.addAppender(appender);
        Level previous = null;
        String loweredName = null;
        if (toLower != null) {
            previous = toLower.getLevel();
            loweredName = toLower.getName();
            toLower.setLevel(loweredTo);
        }
        return new CapturedLog(logger, appender, loweredName, previous);
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

    /**
     * Everything each captured event would actually put in front of a reader: the formatted
     * message and, where one was attached, the whole rendered exception.
     *
     * <p>The privacy claim is about what reaches a log index, and a stack trace reaches it exactly
     * as the message does. An assertion that read only {@link #messages()} would miss the commonest
     * way a payload fragment or a credential escapes — inside the text of an exception somebody
     * else wrote.
     */
    public List<String> renderings() {
        return events().stream().map(CapturedLog::rendering).toList();
    }

    /**
     * The same rendering, for one event a suite is holding on its own.
     *
     * <p>A suite whose claim is graded by level - forbidden at INFO and above, permitted below it -
     * has to filter the events before it renders them, and a second copy of this method in that
     * suite is how the two come to disagree about whether an attached exception counts.
     *
     * @param event one captured event
     * @return everything it would put in front of a reader
     */
    public static String rendering(final ILoggingEvent event) {
        final IThrowableProxy thrown = event.getThrowableProxy();
        return thrown == null
                ? event.getFormattedMessage()
                : event.getFormattedMessage() + System.lineSeparator()
                        + ThrowableProxyUtil.asString(thrown);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        if (loweredName != null) {
            ((Logger) LoggerFactory.getLogger(loweredName)).setLevel(levelToRestore);
        }
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
