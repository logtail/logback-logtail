package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The configured timeouts apply to every request. Checked against endpoints that time out on purpose, so the outcome
 * depends neither on the network nor on a connection an earlier test left open.
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
        assertTrue(appender.getException() instanceof IOException);
        assertEquals("connect timed out", appender.getException().getMessage().toLowerCase());
    }

    @Test
    public void testReadTimeout() throws IOException {
        // Accepts connections but never answers
        try (ServerSocket silentEndpoint = new ServerSocket(0)) {
            appender.setIngestUrl("http://127.0.0.1:" + silentEndpoint.getLocalPort());
            appender.readTimeout = 1;
            logger.error("I am no Groot");
            appender.flush();
            assertTrue(appender.getException() instanceof IOException);
            assertEquals("read timed out", appender.getException().getMessage().toLowerCase());
        }
    }
}
