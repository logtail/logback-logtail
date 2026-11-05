package com.logtail.logback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;

import java.io.IOException;

/**
 * Sends to a {@link RecordingEndpoint} in the test JVM, not to Better Stack.
 *
 * @author tomas@logtail.com
 */
public class LogtailAppenderXmlConfigTest {

    private RecordingEndpoint endpoint;

    @Before
    public void init() throws JoranException, IOException {
        endpoint = new RecordingEndpoint();

        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();
        loggerContext.putProperty("ENDPOINT", endpoint.url());

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logback-prod.xml");
    }

    @After
    public void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).reset();
        endpoint.close();
    }

    @Test
    public void testLogtailAppenderConfiguration() {

        Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");

        LogtailAppender appender = (LogtailAppender) rootLogger.getAppender("Logtail");
        assertNotNull(appender);

        assertEquals(endpoint.url(), appender.ingestUrl);
        assertEquals("source-token", appender.sourceToken);
        assertEquals("BetterStackTest", appender.appName);

        assertEquals(2, appender.mdcFields.size());
        assertEquals("requestId", appender.mdcFields.get(0));
        assertEquals("requestTime", appender.mdcFields.get(1));

        assertEquals(2, appender.mdcTypes.size());
        assertEquals("string", appender.mdcTypes.get(0));
        assertEquals("int", appender.mdcTypes.get(1));

        assertEquals(5000, appender.connectTimeout);
        assertEquals(10000, appender.readTimeout);
        assertEquals(30000, appender.maxFlushTime);
        
        rootLogger.info("I am Groot");
    }

}