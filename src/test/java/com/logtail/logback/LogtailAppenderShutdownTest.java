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

import static org.junit.Assert.assertEquals;

/**
 * Sends to a {@link RecordingEndpoint} in the test JVM, not to Better Stack.
 *
 * @author tomas@logtail.com
 */
public class LogtailAppenderShutdownTest {

    private Logger logger = (Logger) LoggerFactory.getLogger(LogtailAppenderShutdownTest.class);

    private LogtailAppenderDecorator appender;

    private RecordingEndpoint endpoint;

    @Before
    public void init() throws JoranException, IOException {
        endpoint = new RecordingEndpoint();

        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();
        loggerContext.putProperty("ENDPOINT", endpoint.url());

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logback-shutdown-test.xml");

        Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");
        appender = (LogtailAppenderDecorator) rootLogger.getAppender("Logtail");
        assertEquals(10, appender.getBatchSize());
    }

    @After
    public void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        endpoint.close();
    }

    @Test
    public void testAppend() throws Exception {
        // This is to easily identify and diagnose messages coming from the same test run
        String batchRunId = UUID.randomUUID().toString().toLowerCase().replace("-", "");

        for (int i = 0; i < 5; i++) {
            MDC.put("requestId", "testErrorLog");
            MDC.put("requestTime", i + "");
            this.logger.info(batchRunId + " Shutdown hook test" + i);
            assertEquals(0, this.appender.apiCalls);
        }
        Thread.sleep(2000);
        assertEquals(0, this.appender.apiCalls);

        // Stopping sends what is queued
        this.appender.stop();
        assertEquals(Collections.singletonList(5), endpoint.batchSizes());
    }
}