package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Sends to a {@link RecordingEndpoint} in the test JVM, not to Better Stack.
 *
 * @author tomas@logtail.com
 */
public class LogtailAppenderIntegrationTest {

    private Logger logger = (Logger) LoggerFactory.getLogger(LogtailAppenderIntegrationTest.class);

    private LogtailAppenderDecorator appender;

    private RecordingEndpoint endpoint;

    @Before
    public void init() throws JoranException, IOException {
        endpoint = new RecordingEndpoint();

        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logback.xml");

        this.appender = new LogtailAppenderDecorator();
        this.appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        this.appender.setSourceToken("source-token");
        this.appender.setIngestUrl(endpoint.url());
        this.appender.start();

        this.logger.addAppender(appender);
    }

    @After
    public void tearDown() {
        this.logger.detachAppender(appender);
        this.appender.stop();
        endpoint.close();
    }

    @Test
    public void testInfoLog() {
        MDC.put("requestId", "testInfoLog");
        MDC.put("requestTime", "123");
        this.logger.info("I am Groot");
        this.appender.flush();
        isOk();
        assertEquals(Collections.singletonList("I am Groot"), endpoint.messages());
    }

    @Test
    public void testJsonLog() {
        MDC.put("requestId", "testJsonLog");
        MDC.put("requestTime", "456");
        this.logger.info("I am { \"name\": \"Groot\", \"id\": \"GROOT\" }");
        this.appender.flush();
        isOk();
        assertEquals(Collections.singletonList("I am { \"name\": \"Groot\", \"id\": \"GROOT\" }"), endpoint.messages());
    }

    @Test
    public void testWarnLog() {
        MDC.put("requestId", "testWarnLog");
        MDC.put("requestTime", "666");
        this.logger.warn("I AM groot");
        this.appender.flush();
        isOk();
        assertEquals(Collections.singletonList("I AM groot"), endpoint.messages());
    }

    @Test
    public void testErrorLog() {
        MDC.put("requestId", "testErrorLog");
        MDC.put("requestTime", "789");
        this.logger.error("I am Groot?", new RuntimeException("GROOT!"));
        this.appender.flush();
        isOk();
        assertEquals(Collections.singletonList("I am Groot?"), endpoint.messages());
    }

    @Test
    public void testBatchDefaultBatchSize() throws InterruptedException {
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

        // Wait for async flush to be called
        Thread.sleep(10);
        assertEquals(1, this.appender.apiCalls);

        appender.awaitFlushCompletion();

        isOk();
        assertEquals(Collections.singletonList(1000), endpoint.batchSizes());
    }

    @Test
    public void testBatchInterval() throws InterruptedException {
        this.appender.setBatchInterval(500);

        MDC.put("requestId", "testBatchIntervalLog");
        MDC.put("requestTime", "456");
        this.logger.info("Single Batch Groot");
        assertEquals(0, this.appender.apiCalls);

        Thread.sleep(50);
        assertEquals(0, this.appender.apiCalls);

        Thread.sleep(500);
        assertEquals(1, this.appender.apiCalls);

        appender.awaitFlushCompletion();

        isOk();
        assertEquals(Collections.singletonList("Single Batch Groot"), endpoint.messages());
    }

    private void isOk() {
        if (!appender.isOK() && appender.hasError()) {
            System.out.println(appender.getResponse().getStatus() + " - " + appender.getResponse().getError());
        }
        if (!appender.isOK() && appender.hasException()) {
            appender.getException().printStackTrace();
        }
        assertTrue(appender.isOK());
    }

}