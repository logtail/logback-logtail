package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The deprecated ingestKey setting is used as the source token when no sourceToken is set, and sourceToken wins when
 * both are. Sends to a {@link RecordingEndpoint} in the test JVM, not to Better Stack.
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
    public void testLegacyConfigWithOnlyAnIngestKeyKeepsTheAppenderEnabled() throws JoranException, IOException {
        try (RecordingEndpoint endpoint = new RecordingEndpoint()) {
            LoggerContext context = new LoggerContext();
            context.putProperty("ENDPOINT", endpoint.url());
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure("src/test/resources/logback-legacy-ingest-key.xml");
            Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
            LogtailAppender appender = (LogtailAppender) rootLogger.getAppender("Logtail");

            rootLogger.info("I am Groot");

            assertFalse(appender.isDisabled());
            context.stop();
            assertEquals(Collections.singletonList("Bearer ingest-key"),
                    endpoint.requests().stream().map(request -> request.authorization).collect(Collectors.toList()));
        }
    }
}
