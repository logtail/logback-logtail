package com.logtail.logback;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ! One LOGTAIL_INGEST_KEY must be set as an environment variable before launching the test !
 *
 * @author tomas@logtail.com
 */
public class LogtailAppenderBatchConfigSizeTest {

    private Logger logger = (Logger) LoggerFactory.getLogger(LogtailAppenderBatchConfigSizeTest.class);

    private LogtailAppenderDecorator appender;

    @Before
    public void init() throws JoranException {
        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logback-batch-test.xml");

        Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");
        AsyncAppender asyncAppender = (AsyncAppender) rootLogger.getAppender("Logtail");
        appender = (LogtailAppenderDecorator) asyncAppender.getAppender("LogtailHttp");
        assertEquals(200, appender.getBatchSize());
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
        Thread.sleep(4000);
        assertEquals(1, this.appender.apiCalls);

        isOk();
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