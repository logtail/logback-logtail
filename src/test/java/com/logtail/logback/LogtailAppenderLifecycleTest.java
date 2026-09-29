package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * start() starts the appender, sender thread included, and does so once; an appender that never starts has no thread
 * to leave behind, and the batch interval only reschedules a sender that is running.
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
}
