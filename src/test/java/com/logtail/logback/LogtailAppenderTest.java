package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.Assert.*;

public class LogtailAppenderTest {

    @SuppressWarnings({
            "unchecked", "rawtypes"
    })
    @Test
    public void testInfoLog() throws InterruptedException {

        // Fake logging event
        MDC.put("field1", "stringValue");
        MDC.put("field2", "stringValue2");
        MDC.put("field3", "123");
        MDC.put("field4", "true");
        MDC.put("field5", "456");

        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);
        ILoggingEvent ev = new LoggingEvent(Logger.FQCN, logger, Level.INFO, "My log message", null, new Object[]{});

        // Self instantiated appender
        LogtailAppender appender = new LogtailAppender();
        appender.setAppName("BetterStackTest");
        appender.setMdcFields("field1,field3,field4,field5");
        appender.setMdcTypes("string,int,boolean,long");

        // Build up future-JSON data
        Map<String, Object> postData = appender.buildPostData(ev);

        assertNotNull(postData);

        assertEventData(ev, postData);
    }

    @SuppressWarnings("unchecked")
    private void assertEventData(ILoggingEvent ev, Map<String, Object> event) {
        assertNotNull(event);
        assertNotNull(event.get("dt"));
        assertNotNull(event.get("level"));
        assertEquals(Level.INFO.toString(), event.get("level"));
        assertNotNull(event.get("app"));
        assertEquals("BetterStackTest", event.get("app"));

        assertNotNull(event.get("meta"));
        Map<String, Object> meta = (Map<String, Object>) event.get("meta");
        assertNotNull(meta.get("logger"));
        assertEquals(ev.getLoggerName(), meta.get("logger"));
        assertNotNull(meta.get("field1"));
        assertEquals("stringValue", meta.get("field1"));
        assertNull(meta.get("field2"));
        assertNotNull(meta.get("field3"));
        assertEquals(123, meta.get("field3"));
        assertNotNull(meta.get("field4"));
        assertEquals(Boolean.TRUE, meta.get("field4"));
        assertNotNull(meta.get("field5"));
        assertEquals(456L, meta.get("field5"));
    }
}