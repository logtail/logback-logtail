package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Logs queued in the appender must reach Better Stack even when the application never stops logback and
 * simply lets the JVM exit, and stop() must not return while a flush is still in progress on another thread.
 */
public class LogtailAppenderJvmExitTest {

    @Test
    public void testQueuedLogsAreSentWhenTheJvmExitsWithoutStoppingLogback() throws Exception {
        List<String> receivedBodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedBodies.add(new Scanner(exchange.getRequestBody(), "UTF-8").useDelimiter("\\A").next());
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            Process app = new ProcessBuilder(
                    System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                    "-cp", System.getProperty("java.class.path"),
                    ExitingApp.class.getName(),
                    "http://127.0.0.1:" + server.getAddress().getPort())
                    .inheritIO()
                    .start();
            if (!app.waitFor(30, TimeUnit.SECONDS)) {
                app.destroyForcibly();
                fail("The app did not exit on its own");
            }
            assertEquals(0, app.exitValue());
        } finally {
            server.stop(0);
        }

        assertEquals(1, receivedBodies.size());
        List<Map<String, Object>> lines = new ObjectMapper().readValue(receivedBodies.get(0), new TypeReference<List<Map<String, Object>>>() {});
        assertEquals(Collections.singletonList("Logged right before the JVM exits"),
                lines.stream().map(line -> line.get("message")).collect(Collectors.toList()));
    }

    /**
     * Run in a JVM of its own: logs once and returns from main without stopping logback.
     */
    public static class ExitingApp {
        public static void main(String[] args) {
            LoggerContext context = new LoggerContext();
            LogtailAppender appender = new LogtailAppender();
            appender.setContext(context);
            appender.setAppName("ExitingApp");
            appender.setSourceToken("source-token");
            appender.setIngestUrl(args[0]);
            appender.start();

            Logger logger = context.getLogger("ExitingApp");
            logger.addAppender(appender);
            logger.info("Logged right before the JVM exits");
        }
    }

    @Test
    public void testStopWaitsForTheFlushInProgressAndSendsWhatQueuedBehindIt() throws Exception {
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
        appender.start();
        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);

        appender.doAppend(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "First", null, new Object[]{}));
        appender.doAppend(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Second", null, new Object[]{}));
        assertTrue("A full batch starts a flush", requestStarted.await(5, TimeUnit.SECONDS));
        appender.doAppend(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Third", null, new Object[]{}));

        Thread stopping = new Thread(appender::stop);
        stopping.start();
        stopping.join(1000);
        assertTrue("stop() must wait for the flush in progress", stopping.isAlive());

        requestMayComplete.countDown();
        stopping.join(5000);
        assertFalse(stopping.isAlive());
        assertEquals(Arrays.asList(2, 1), sentBatchSizes);
    }
}
