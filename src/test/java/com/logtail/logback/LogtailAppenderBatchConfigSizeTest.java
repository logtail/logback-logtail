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
import static org.junit.Assert.assertTrue;

/**
 * Sends to a {@link RecordingEndpoint} in the test JVM, not to Better Stack.
 *
 * @author tomas@logtail.com
 */
public class LogtailAppenderBatchConfigSizeTest {

    private Logger logger = (Logger) LoggerFactory.getLogger(LogtailAppenderBatchConfigSizeTest.class);

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
        configurator.doConfigure("src/test/resources/logback-batch-test.xml");

        Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");
        appender = (LogtailAppenderDecorator) rootLogger.getAppender("Logtail");
        assertEquals(200, appender.getBatchSize());
    }

    @After
    public void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        endpoint.close();
    }

    @Test
    public void testBatchSizeFromConfig() throws Exception {
        // This is to easily identify and diagnose messages coming from the same test run
        String batchRunId = UUID.randomUUID().toString().toLowerCase().replace("-", "");

        for (int i = 0; i < 100; i++) {
            MDC.put("requestId", "testErrorLog");
            MDC.put("requestTime", i + "");
            this.logger.info(batchRunId + " Custom batch size Batch Groot " + i);
            assertEquals(0, this.appender.apiCalls);
        }
        for (int i = 0; i < 99; i++) {
            MDC.put("requestId", "testErrorLog");
            MDC.put("requestTime", (100 + i) + "");
            this.logger.info(batchRunId + " Custom batch sizeBatch Groot " + (100 + i));
            assertEquals(0, this.appender.apiCalls);
        }

        MDC.put("requestId", "testErrorLog");
        MDC.put("requestTime", 199 + "");
        this.logger.info(batchRunId + " Custom batch size Final Batch Groot ");

        // The 200th line sends the batch on another thread
        this.appender.awaitFlushCompletion();

        isOk();
        assertEquals(1, this.appender.acceptedCalls);
        assertEquals(Collections.singletonList(200), endpoint.batchSizes());
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