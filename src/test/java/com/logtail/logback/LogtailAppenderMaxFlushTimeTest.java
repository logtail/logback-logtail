package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.Test;

import java.io.File;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * stop() and the JVM shutdown hook send what is queued, but an endpoint that never answers must not hold the
 * application's shutdown for longer than maxFlushTime (1 second in logback-max-flush-time.xml).
 */
public class LogtailAppenderMaxFlushTimeTest {

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
            assertTrue(appender.batch.isEmpty());
        }
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
