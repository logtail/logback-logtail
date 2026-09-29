package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The configured timeouts apply to every request. Checked against endpoints that time out on purpose, so the outcome
 * depends neither on the network nor on a connection an earlier test left open. A negative timeout, which
 * HttpURLConnection does not take, is ignored with a warning; 0 still means no timeout.
 */
public class LogtailAppenderTimeoutTest {

    private Logger logger;

    private LogtailAppenderDecorator appender;

    @Before
    public void init() {
        LoggerContext context = new LoggerContext();
        appender = new LogtailAppenderDecorator();
        appender.setContext(context);
        appender.setSourceToken("source-token");
        appender.setMaxRetries(0);
        appender.start();
        logger = context.getLogger("TimeoutTest");
        logger.addAppender(appender);
    }

    @Test
    public void testConnectTimeout() {
        // Non-routable, nothing ever answers the connection attempt
        appender.setIngestUrl("http://10.255.255.1");
        appender.connectTimeout = 1;
        logger.error("I am no Groot");
        appender.flush();
        // Not its message: a timeout this short can run out before the connection attempt starts, and the exception
        // has no message then
        assertTrue(appender.getException() instanceof SocketTimeoutException);
    }

    @Test
    public void testReadTimeout() throws IOException {
        // Accepts connections but never answers
        try (ServerSocket silentEndpoint = new ServerSocket(0)) {
            appender.setIngestUrl("http://127.0.0.1:" + silentEndpoint.getLocalPort());
            appender.readTimeout = 1;
            logger.error("I am no Groot");
            appender.flush();
            assertTrue(appender.getException() instanceof SocketTimeoutException);
        }
    }

    @Test
    public void testANegativeTimeoutKeepsTheTimeoutSetBeforeWithAWarning() {
        appender.setConnectTimeout(0);
        appender.setConnectTimeout(-1);
        appender.setReadTimeout(0);
        appender.setReadTimeout(-1);

        assertEquals(0, appender.connectTimeout);
        assertEquals(0, appender.readTimeout);
        assertEquals(Arrays.asList(
                "connectTimeout must be 0 (no timeout) or more, keeping 0 ms instead of -1",
                "readTimeout must be 0 (no timeout) or more, keeping 0 ms instead of -1"),
                appender.getContext().getStatusManager().getCopyOfStatusList().stream()
                        .filter(status -> status.getLevel() == Status.WARN)
                        .map(Status::getMessage)
                        .collect(Collectors.toList()));
    }
}
