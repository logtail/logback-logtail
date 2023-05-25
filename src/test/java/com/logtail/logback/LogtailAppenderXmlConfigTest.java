package com.logtail.logback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;

/**
 * ! LOGTAIL_INGEST_KEY  must be set as an environment variable before launching the test !
 * 
 * @author tomas@logtail.com
 */
public class LogtailAppenderXmlConfigTest {

    @Before
    public void init() throws JoranException {
        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logback-prod.xml");
    }

    @Test
    public void testLogtailAppenderConfiguration() {

        Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");
        AsyncAppender asyncAppender = (AsyncAppender) rootLogger.getAppender("Logtail");
        assertNotNull("Logtail AsyncAppender not found, meaning that the xmlConfig property set up failed", asyncAppender);

        LogtailAppender appender = (LogtailAppender) asyncAppender.getAppender("LogtailHttp");
        assertNotNull(appender);

        assertNotNull(appender.ingestUrl);
        assertTrue(appender.headers.containsKey("Authorization"));
        assertEquals("BetterStackTest", appender.appName);

        assertEquals(2, appender.mdcFields.size());
        assertEquals("requestId", appender.mdcFields.get(0));
        assertEquals("requestTime", appender.mdcFields.get(1));

        assertEquals(2, appender.mdcTypes.size());
        assertEquals("string", appender.mdcTypes.get(0));
        assertEquals("int", appender.mdcTypes.get(1));

        assertEquals(5000, appender.connectTimeout);
        assertEquals(10000, appender.readTimeout);
        
        rootLogger.info("I am Groot");
    }

}