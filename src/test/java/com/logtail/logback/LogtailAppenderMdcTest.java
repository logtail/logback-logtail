package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/**
 * Events are queued in append() and serialized later on the sender thread, so everything taken from the logging
 * thread (its MDC, its name) has to be captured while the event is still on that thread.
 */
public class LogtailAppenderMdcTest {

    @Test
    public void testMdcAndThreadNameAreCapturedWhenTheEventIsQueued() throws Exception {
        LoggerContext context = new LoggerContext();
        LogtailAppender appender = new LogtailAppender();
        appender.setContext(context);
        appender.setSourceToken("source-token");
        appender.setMdcFields("requestId");
        appender.setMdcTypes("string");
        appender.start();
        Logger logger = context.getLogger("mdc-test");
        logger.addAppender(appender);

        MDC.put("requestId", "req-42");
        try {
            logger.info("Queued while the MDC was set");
        } finally {
            MDC.remove("requestId");
        }

        AtomicReference<String> json = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try {
                json.set(appender.batchToJson(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "logtail-appender");
        sender.start();
        sender.join();

        Map<String, Object> line = new ObjectMapper().readValue(json.get(), new TypeReference<List<Map<String, Object>>>() {}).get(0);
        Map<String, Object> meta = new HashMap<>();
        meta.put("logger", "mdc-test");
        meta.put("requestId", "req-42");
        assertEquals(meta, line.get("meta"));
        assertEquals(Collections.singletonMap("thread", "main"), line.get("runtime"));
    }
}
