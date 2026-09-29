package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The deprecated ingestKey setting is used as the source token when no sourceToken is set, and sourceToken wins when
 * both are.
 */
public class LogtailAppenderIngestKeyTest {

    @Test
    public void testIngestKeyIsTheSourceTokenWhenNoSourceTokenIsSet() {
        LogtailAppender appender = new LogtailAppender();
        appender.setIngestKey("ingest-key");
        assertEquals("ingest-key", appender.sourceToken);
    }

    @Test
    public void testSourceTokenWinsOverIngestKeyInEitherOrder() {
        LogtailAppender ingestKeyFirst = new LogtailAppender();
        ingestKeyFirst.setIngestKey("ingest-key");
        ingestKeyFirst.setSourceToken("source-token");
        assertEquals("source-token", ingestKeyFirst.sourceToken);

        LogtailAppender sourceTokenFirst = new LogtailAppender();
        sourceTokenFirst.setSourceToken("source-token");
        sourceTokenFirst.setIngestKey("ingest-key");
        assertEquals("source-token", sourceTokenFirst.sourceToken);
    }

    @Test
    public void testLegacyConfigWithOnlyAnIngestKeyKeepsTheAppenderEnabled() throws JoranException {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure("src/test/resources/logback-legacy-ingest-key.xml");
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        LogtailAppender appender = (LogtailAppender) rootLogger.getAppender("Logtail");

        rootLogger.info("I am Groot");

        assertFalse(appender.isDisabled());
    }
}
