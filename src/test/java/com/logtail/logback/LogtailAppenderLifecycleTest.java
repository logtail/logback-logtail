package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.Status;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * start() starts the appender, sender thread included, and does so once; an appender that never starts has no thread
 * to leave behind, and the batch interval only reschedules a sender that is running. A batch interval below 1 ms, which
 * the sender cannot be scheduled with, is ignored with a warning.
 */
public class LogtailAppenderLifecycleTest {

    @Test
    public void testAnAppenderThatNeverStartsLeavesNoThreadBehind() {
        Set<Thread> before = senderThreads();

        LogtailAppender appender = new LogtailAppender();
        appender.setBatchInterval(100);
        appender.stop();

        assertEquals(Collections.emptySet(), newSince(before));
    }

    @Test
    public void testStartingTwiceKeepsOneShutdownHook() {
        LogtailAppender appender = new SendingAppender();
        appender.start();
        Thread hook = appender.shutdownHook;
        appender.start();
        appender.stop();

        assertFalse("stop() must remove the hook start() registered", Runtime.getRuntime().removeShutdownHook(hook));
    }

    @Test
    public void testTheBatchIntervalSetBeforeStartIsUsed() throws Exception {
        SendingAppender appender = new SendingAppender();
        appender.setBatchInterval(100);
        appender.start();
        try {
            appender.doAppend(event("Sent on the configured schedule"));
            assertTrue(appender.sent.await(2, TimeUnit.SECONDS));
        } finally {
            appender.stop();
        }
    }

    @Test
    public void testChangingTheBatchIntervalReschedulesARunningSender() throws Exception {
        SendingAppender appender = new SendingAppender();
        appender.start();
        try {
            appender.setBatchInterval(100);
            appender.doAppend(event("Sent on the new schedule"));
            assertTrue("The default 3 second schedule would not have sent it yet", appender.sent.await(2, TimeUnit.SECONDS));
        } finally {
            appender.stop();
        }
    }

    @Test
    public void testChangingTheBatchIntervalOfAStoppedAppenderStartsNothing() {
        LogtailAppender appender = new SendingAppender();
        appender.start();
        appender.stop();
        Set<Thread> before = senderThreads();

        appender.setBatchInterval(100);

        assertEquals(Collections.emptySet(), newSince(before));
    }

    @Test
    public void testARestartedAppenderSendsOnTheScheduleAgain() throws Exception {
        SendingAppender appender = new SendingAppender();
        appender.setBatchInterval(100);
        appender.start();
        appender.stop();
        appender.start();
        try {
            appender.doAppend(event("Sent after the restart"));
            assertTrue(appender.sent.await(2, TimeUnit.SECONDS));
        } finally {
            appender.stop();
        }
    }

    @Test
    public void testABatchIntervalBelow1MsInTheConfigKeepsTheDefaultWithAWarning() throws Exception {
        CountDownLatch sent = new CountDownLatch(2);
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", exchange -> {
            sent.countDown();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        endpoint.start();
        LoggerContext context = new LoggerContext();
        context.putProperty("ENDPOINT", "http://127.0.0.1:" + endpoint.getAddress().getPort());
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(LogtailAppenderLifecycleTest.class.getResource("/logback-batch-interval.xml"));

            assertEquals("Spring Boot refuses to start on a logback error", Collections.emptyList(),
                    context.getStatusManager().getCopyOfStatusList().stream()
                            .filter(status -> status.getLevel() == Status.ERROR)
                            .map(Status::toString)
                            .collect(Collectors.toList()));
            assertEquals(Arrays.asList(
                    "batchInterval must be positive, keeping 3000 ms instead of 0",
                    "batchInterval must be positive, keeping 3000 ms instead of -1"), warnings(context));
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            LogtailAppender zero = (LogtailAppender) root.getAppender("Zero");
            LogtailAppender negative = (LogtailAppender) root.getAppender("Negative");
            assertTrue(zero.isStarted());
            assertTrue(negative.isStarted());
            assertEquals(3000, zero.batchInterval);
            assertEquals(3000, negative.batchInterval);

            root.info("Sent by both appenders on the default 3 second schedule");
            assertTrue(sent.await(10, TimeUnit.SECONDS));
        } finally {
            context.stop();
            endpoint.stop(0);
        }
    }

    @Test
    public void testABatchIntervalBelow1MsKeepsTheIntervalSetBefore() throws Exception {
        SendingAppender appender = new SendingAppender();
        appender.setBatchInterval(100);
        appender.setBatchInterval(0);
        appender.start();
        try {
            appender.doAppend(event("Sent on the 100 ms schedule"));
            assertTrue("The default 3 second schedule would not have sent it yet", appender.sent.await(2, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("batchInterval must be positive, keeping 100 ms instead of 0"),
                    warnings(appender.getContext()));
        } finally {
            appender.stop();
        }
    }

    @Test
    public void testABatchIntervalBelow1MsLeavesARunningSenderOnItsSchedule() throws Exception {
        SendingAppender appender = new SendingAppender();
        appender.setBatchInterval(100);
        appender.start();
        try {
            appender.setBatchInterval(-5);
            appender.doAppend(event("Sent on the 100 ms schedule"));
            assertTrue(appender.sent.await(2, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("batchInterval must be positive, keeping 100 ms instead of -5"),
                    warnings(appender.getContext()));
        } finally {
            appender.stop();
        }
    }

    /**
     * Counts the requests instead of sending them.
     */
    private static class SendingAppender extends LogtailAppender {
        final CountDownLatch sent = new CountDownLatch(1);

        SendingAppender() {
            setContext(new LoggerContext());
            setSourceToken("source-token");
        }

        @Override
        protected LogtailResponse callHttpURLConnection(int flushedSize) {
            sent.countDown();
            return new LogtailResponse(null, 202);
        }
    }

    private static LoggingEvent event(String message) {
        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);
        return new LoggingEvent(Logger.FQCN, logger, Level.INFO, message, null, new Object[]{});
    }

    private static Set<Thread> senderThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("logtail-appender") && thread.isAlive())
                .collect(Collectors.toSet());
    }

    private static Set<Thread> newSince(Set<Thread> before) {
        Set<Thread> now = senderThreads();
        now.removeAll(before);
        return now;
    }

    private static List<String> warnings(Context context) {
        return context.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() == Status.WARN)
                .map(Status::getMessage)
                .collect(Collectors.toList());
    }
}
