package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import jakarta.ws.rs.ProcessingException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * ! One LOGTAIL_INGEST_KEY must be set as an environment variable before launching the test !
 *
 *
 * @author tomas@logtail.com
 */
public class LogtailAppenderIntegrationTest {

    private Logger logger = (Logger) LoggerFactory.getLogger(LogtailAppenderIntegrationTest.class);

    private LogtailAppenderDecorator appender;

    @Before
    public void init() throws JoranException {
        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logback.xml");

        this.appender = new LogtailAppenderDecorator();
        this.appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        this.appender.setSourceToken(System.getenv("BETTER_STACK_SOURCE_TOKEN"));
        this.appender.start();

        this.logger.addAppender(appender);
    }

    @After
    public void tearDown() {
        this.logger.detachAppender(appender);
    }

    // Not anymore an error : appender is automatically disabled
    //    @Test
    //    public void testNoCredentials() {
    //        this.appender.headers.remove("apikey");
    //        this.logger.error("I am no Groot");
    //        hasError("Missing Credentials", 401);
    //    }

    @Test
    public void testInfoLog() {
        MDC.put("requestId", "testInfoLog");
        MDC.put("requestTime", "123");
        this.logger.info("I am Groot");
        this.appender.flush();
        isOk();
    }

    @Test
    public void testJsonLog() {
        MDC.put("requestId", "testJsonLog");
        MDC.put("requestTime", "456");
        this.logger.info("I am { \"name\": \"Groot\", \"id\": \"GROOT\" }");
        this.appender.flush();
        isOk();
    }

    @Test
    public void testWarnLog() {
        MDC.put("requestId", "testWarnLog");
        MDC.put("requestTime", "666");
        this.logger.warn("I AM groot");
        this.appender.flush();
        isOk();
    }

    @Test
    public void testErrorLog() {
        MDC.put("requestId", "testErrorLog");
        MDC.put("requestTime", "789");
        this.logger.error("I am Groot?", new RuntimeException("GROOT!"));
        this.appender.flush();
        isOk();
    }

    @Test
    public void testBatchDefaultBatchSize() {
        // This is to easily identify and diagnose messages coming from the same test run
        String batchRunId = UUID.randomUUID().toString().toLowerCase().replace("-", "");

        for (int i = 0; i < 500; i++) {
            MDC.put("requestId", "testErrorLog");
            MDC.put("requestTime", i + "");
            this.logger.info(batchRunId + " Batch Groot " + i);
            assertEquals(0, this.appender.apiCalls);
        }
        for (int i = 0; i < 499; i++) {
            MDC.put("requestId", "testErrorLog");
            MDC.put("requestTime", (500 + i) + "");
            this.logger.info(batchRunId + " Batch Groot " + (500 + i));
            assertEquals(0, this.appender.apiCalls);
        }
        MDC.put("requestId", "testErrorLog");
        MDC.put("requestTime", 999 + "");
        this.logger.info(batchRunId + " Final Batch Groot ");
        assertEquals(1, this.appender.apiCalls);

        isOk();
    }

    @Test
    public void testConnectTimeout() {
        this.appender.setConnectTimeout(2L);
        this.logger.error("I am no Groot");
        this.appender.flush();
        assertTrue(appender.hasException());
        assertTrue(ProcessingException.class.isInstance(appender.getException()));
        assertNotNull(appender.getException().getCause());
        assertNotNull(appender.getException().getCause().getMessage());
        assertEquals("connect timed out", appender.getException().getCause().getMessage().toLowerCase());
    }

    @Test
    public void testReadTimeout() {
        this.appender.setReadTimeout(2L);
        this.logger.error("I am no Groot");
        this.appender.flush();
        assertTrue(appender.hasException());
        assertTrue(ProcessingException.class.isInstance(appender.getException()));
        assertNotNull(appender.getException().getCause());
        assertEquals("Read timed out", appender.getException().getCause().getMessage());
    }

    private void hasError(String message, int statusCode) {
        assertFalse(appender.hasException());
        assertFalse(appender.isOK());
        assertTrue(appender.hasError());
        assertEquals(message, appender.getLogtailResponse().getError());
        assertEquals(statusCode, appender.getResponse().getStatus());
    }

    private void isOk() {
        if (!appender.isOK() && appender.hasError()) {
            System.out.println(appender.getLogtailResponse().getStatus() + " - " + appender.getLogtailResponse().getError());
        }
        if (!appender.isOK() && appender.hasException()) {
            appender.getException().printStackTrace();
        }
        assertTrue(appender.isOK());
    }

}