package com.logtail.logback;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;

/**
 * @author tomas@logtail.com
 */
public class LogtailAppenderXmlEmptyConfigTest {

    @Before
    public void init() throws JoranException {
        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logback-disabled.xml");
    }

    @Test
    public void testLogtailAppenderConfiguration() {
        Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");

        LogtailAppender appender = (LogtailAppender) rootLogger.getAppender("LogtailHttp");
        assertNotNull(appender);
        
        rootLogger.info("I am Groot");

        assertTrue(appender.isDisabled());
    }

}