package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.Status;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * stop() and the JVM shutdown hook send what is queued, but nothing may hold the application's shutdown for longer
 * than maxFlushTime (1 second in logback-max-flush-time.xml): not an endpoint that never answers, not one that
 * stopped taking data, not a request without a timeout of its own - unless maxFlushTime is 0, which means no limit,
 * as for logback's own AsyncAppender. A negative maxFlushTime, which Thread.join() does not take, is ignored with a
 * warning.
 */
public class LogtailAppenderMaxFlushTimeTest {

    private static final Logger LOGGER = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);

    @Test
    public void testStopGivesUpOnAnEndpointThatNeverAnswers() throws Exception {
        // Accepts connections but never answers: every attempt would wait for the full 10 second read timeout
        try (ServerSocket silentEndpoint = new ServerSocket(0)) {
            LoggerContext context = configure("http://127.0.0.1:" + silentEndpoint.getLocalPort(), "1000");
            Logger logger = context.getLogger("MaxFlushTimeTest");
            logger.info("Never sent 1");
            logger.info("Never sent 2");
            logger.info("Never sent 3");
            LogtailAppender appender = (LogtailAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("Logtail");

            Thread stopping = new Thread(appender::stop);
            stopping.setDaemon(true);
            stopping.start();
            stopping.join(3000);

            assertFalse("stop() must give up after maxFlushTime", stopping.isAlive());
        }
    }

    @Test
    public void testStopGivesUpOnAnEndpointThatNeverAnswersARequestWithoutReadTimeout() throws Exception {
        // readTimeout 0 turns the request's own timeout off, so only maxFlushTime can end the wait for an answer
        try (ServerSocket silentEndpoint = new ServerSocket(0)) {
            LogtailAppender appender = new LogtailAppender();
            appender.setReadTimeout(0);
            start(appender, "http://127.0.0.1:" + silentEndpoint.getLocalPort());
            queue(appender, "Never sent");

            assertStopGivesUp(appender);
        }
    }

    @Test
    public void testStopGivesUpOnAnEndpointThatStoppedTakingData() throws Exception {
        // The batch is bigger than what the connection's buffers take and nobody reads it on the other end. Writing
        // to a socket has no timeout at all, so only maxFlushTime can end the wait
        try (ServerSocket silentEndpoint = new ServerSocket()) {
            silentEndpoint.setReceiveBufferSize(1024);
            silentEndpoint.bind(new InetSocketAddress("127.0.0.1", 0));
            LogtailAppender appender = new LogtailAppender();
            start(appender, "http://127.0.0.1:" + silentEndpoint.getLocalPort());
            char[] line = new char[4 * 1024];
            Arrays.fill(line, 'x');
            String message = new String(line);
            // One short of the default batchSize, so that no flush starts before stop()
            for (int i = 0; i < 999; i++)
                queue(appender, message);

            assertStopGivesUp(appender);
        }
    }

    @Test
    public void testStopSendsTheQueueOnAnInterruptedThread() throws Exception {
        List<Integer> sentBatchSizes = new CopyOnWriteArrayList<>();
        LogtailAppender appender = new LogtailAppender() {
            @Override
            protected LogtailResponse callHttpURLConnection(int flushedSize) {
                sentBatchSizes.add(flushedSize);
                return new LogtailResponse(null, 202);
            }
        };
        start(appender, "http://127.0.0.1");
        queue(appender, "Sent by an interrupted thread");

        AtomicBoolean interruptedAfterStop = new AtomicBoolean();
        Thread stopping = new Thread(() -> {
            Thread.currentThread().interrupt();
            appender.stop();
            interruptedAfterStop.set(Thread.currentThread().isInterrupted());
        });
        stopping.start();
        stopping.join(5000);

        assertEquals(Collections.singletonList(1), sentBatchSizes);
        assertTrue("The interrupt is left for the caller to handle", interruptedAfterStop.get());
    }

    @Test
    public void testLogsNotSentInTimeAreDroppedAfterTheRequestInProgress() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        LogtailAppender appender = new LogtailAppender() {
            @Override
            protected LogtailResponse callHttpURLConnection(int flushedSize) throws IOException {
                requests.incrementAndGet();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                throw new SocketTimeoutException("Read timed out");
            }
        };
        start(appender, "http://127.0.0.1");
        queue(appender, "Never sent");

        appender.stop();
        for (int waited = 0; !appender.batch.isEmpty() && waited < 5000; waited += 50)
            Thread.sleep(50);

        assertTrue(appender.batch.isEmpty());
        assertEquals("No retry once the time is up", 1, requests.get());
    }

    @Test
    public void testJvmExitsWithinMaxFlushTimeWhenAFlushHangsOnTheEndpoint() throws Exception {
        try (ServerSocket silentEndpoint = new ServerSocket(0)) {
            Process app = new ProcessBuilder(
                    System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                    "-cp", System.getProperty("java.class.path"),
                    SilentEndpointApp.class.getName(),
                    "http://127.0.0.1:" + silentEndpoint.getLocalPort())
                    .inheritIO()
                    .start();
            if (!app.waitFor(10, TimeUnit.SECONDS)) {
                app.destroyForcibly();
                fail("The app did not exit within maxFlushTime");
            }
            assertEquals(0, app.exitValue());
        }
    }

    @Test
    public void testZeroMaxFlushTimeWaitsForAFlushInProgressAsLongAsItTakes() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch requestMayComplete = new CountDownLatch(1);
        List<Integer> sentBatchSizes = new CopyOnWriteArrayList<>();
        LogtailAppender appender = new LogtailAppender() {
            @Override
            protected LogtailResponse callHttpURLConnection(int flushedSize) throws IOException {
                requestStarted.countDown();
                try {
                    requestMayComplete.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                sentBatchSizes.add(flushedSize);
                return new LogtailResponse(null, 202);
            }
        };
        appender.setContext(new LoggerContext());
        appender.setSourceToken("source-token");
        appender.setBatchSize(2);
        appender.setMaxFlushTime(0);
        appender.start();
        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);

        try {
            appender.doAppend(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "First", null, new Object[]{}));
            appender.doAppend(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Second", null, new Object[]{}));
            assertTrue("A full batch starts a flush", requestStarted.await(5, TimeUnit.SECONDS));
            appender.doAppend(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Third", null, new Object[]{}));

            Thread stopping = new Thread(appender::stop);
            stopping.start();
            stopping.join(1500);
            assertTrue("stop() must keep waiting for the flush in progress", stopping.isAlive());

            requestMayComplete.countDown();
            stopping.join(5000);
            assertFalse(stopping.isAlive());
            assertEquals(Arrays.asList(2, 1), sentBatchSizes);
        } finally {
            requestMayComplete.countDown();
        }
    }

    @Test
    public void testANegativeMaxFlushTimeKeepsTheDefaultWithAWarning() {
        List<Integer> sentBatchSizes = new CopyOnWriteArrayList<>();
        LogtailAppender appender = new LogtailAppender() {
            @Override
            protected LogtailResponse callHttpURLConnection(int flushedSize) {
                sentBatchSizes.add(flushedSize);
                return new LogtailResponse(null, 202);
            }
        };
        appender.setContext(new LoggerContext());
        appender.setSourceToken("source-token");
        appender.setMaxFlushTime(-1);
        appender.start();
        queue(appender, "Sent by stop()");

        appender.stop();

        assertEquals(Collections.singletonList(1), sentBatchSizes);
        assertEquals(Collections.singletonList("maxFlushTime must be 0 (no limit) or more, keeping 30000 ms instead of -1"),
                appender.getContext().getStatusManager().getCopyOfStatusList().stream()
                        .filter(status -> status.getLevel() == Status.WARN)
                        .map(Status::getMessage)
                        .collect(Collectors.toList()));
    }

    /**
     * Run in a JVM of its own: fills a batch, so a flush starts and hangs on the endpoint, and returns from main.
     */
    public static class SilentEndpointApp {
        public static void main(String[] args) throws JoranException {
            Logger logger = configure(args[0], "2").getLogger("SilentEndpointApp");
            logger.info("First of a full batch");
            logger.info("Second of a full batch");
        }
    }

    private static void start(LogtailAppender appender, String ingestUrl) {
        appender.setContext(new LoggerContext());
        appender.setSourceToken("source-token");
        appender.setIngestUrl(ingestUrl);
        appender.setBatchInterval(60000);
        appender.setMaxFlushTime(500);
        appender.start();
    }

    private static void queue(LogtailAppender appender, String message) {
        appender.doAppend(new LoggingEvent(Logger.FQCN, LOGGER, Level.INFO, message, null, new Object[]{}));
    }

    private static void assertStopGivesUp(LogtailAppender appender) throws InterruptedException {
        Thread stopping = new Thread(appender::stop);
        stopping.setDaemon(true);
        stopping.start();
        stopping.join(3000);

        assertFalse("stop() must give up after maxFlushTime", stopping.isAlive());
    }

    private static LoggerContext configure(String silentEndpoint, String batchSize) throws JoranException {
        LoggerContext context = new LoggerContext();
        context.putProperty("SILENT_ENDPOINT", silentEndpoint);
        context.putProperty("BATCH_SIZE", batchSize);
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(LogtailAppenderMaxFlushTimeTest.class.getResource("/logback-max-flush-time.xml"));
        return context;
    }
}
