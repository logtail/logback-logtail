package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

/**
 * Caller data is optional in the payload, so an event that cannot provide it must not take the whole batch down.
 */
public class LogtailAppenderCallerDataTest {

    @Test
    public void testBatchStillSerializesWhenAnEventThrowsFromHasCallerData() throws Exception {
        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);
        LogtailAppender appender = new LogtailAppender();
        appender.batch.add(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Regular event", null, new Object[]{}));
        appender.batch.add(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Event whose caller data throws", null, new Object[]{}) {
            // The Quarkus logback bridge (io.quarkiverse.logback.runtime.LoggingEventWrapper) rebuilds caller data
            // from the JBoss log record inside hasCallerData() and throws when the record carries no source class
            @Override
            public boolean hasCallerData() {
                throw new NullPointerException("Declaring class is null");
            }
        });

        List<Map<String, Object>> lines = new ObjectMapper().readValue(appender.batchToJson(2), new TypeReference<List<Map<String, Object>>>() {});

        assertEquals(Arrays.asList("Regular event", "Event whose caller data throws"),
                lines.stream().map(line -> line.get("message")).collect(Collectors.toList()));
        assertEquals(Collections.singletonMap("thread", "main"), lines.get(1).get("runtime"));
    }
}
