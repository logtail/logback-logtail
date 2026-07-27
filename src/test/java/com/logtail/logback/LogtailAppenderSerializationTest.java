package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A log argument that Jackson cannot serialize - a cyclic object graph (e.g. a JDBC Connection, which
 * HikariCP logs on every pooled connection creation), or a value whose getter throws - must never
 * prevent the batch from being sent. The unserializable data itself is omitted and replaced with a
 * marker string; everything around it stays structured JSON.
 */
public class LogtailAppenderSerializationTest {

    // Stand-in for any cyclic object graph a dependency might log, e.g. PgConnection <-> PgDatabaseMetaData
    static class Parent {
        Child child;

        public Child getChild() {
            return child;
        }
    }

    static class Child {
        Parent parent;

        public Parent getParent() {
            return parent;
        }
    }

    // Stand-in for any argument Jackson chokes on for a reason other than a cycle
    static class Unserializable {
        public String getValue() {
            throw new UnsupportedOperationException("not available");
        }
    }

    private LogtailAppender appender;
    private Logger logger;

    @Before
    public void setUp() {
        logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);

        appender = new LogtailAppender();
        appender.setAppName("BetterStackTest");
        appender.setSourceToken("dummy-token"); // any non-empty value; no request is made by these tests
    }

    @Test
    public void testCyclicArgumentIsOmittedAtTheCycleOnly() throws Exception {
        Parent parent = new Parent();
        Child child = new Child();
        parent.child = child;
        child.parent = parent; // the cycle

        log("Log line before the cyclic one");
        // Equivalent to HikariPool's: LOGGER.debug("{} - Added connection {}", poolName, poolEntry.connection);
        log("Some object graph: {} and a sibling argument: {}", parent, Collections.singletonMap("sibling", true));
        log("Log line after the cyclic one: {}", Collections.singletonMap("orderId", 42));

        String json = appender.batchToJson(3);

        assertTrue("Log line before the cyclic one must be sent", json.contains("Log line before the cyclic one"));
        assertTrue("The cyclic log line itself must be sent", json.contains("Some object graph:"));
        assertTrue("Log line after the cyclic one must be sent", json.contains("Log line after the cyclic one:"));

        assertTrue("The cyclic argument must stay structured up to the point where it loops back",
                json.contains("{\"Child\":{\"Parent\":\"<omitted circular reference>\"}}"));
        assertTrue("Sibling arguments of the cyclic one must stay structured",
                json.contains("{\"sibling\":true}"));
        assertTrue("Arguments of other log lines must stay structured",
                json.contains("{\"orderId\":42}"));
    }

    @Test
    public void testCyclicContainerArgumentsAreOmittedAtTheCycleOnly() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("self", map);
        List<Object> list = new ArrayList<>();
        list.add(list);
        Object[] array = new Object[1];
        array[0] = array;

        log("A cyclic map: {}", map);
        log("A cyclic list: {}", list);
        log("A cyclic array: {}", new Object[]{array});

        String json = appender.batchToJson(3);

        assertTrue("The cyclic map must be sent with its cycle omitted",
                json.contains("{\"self\":\"<omitted circular reference>\"}"));
        assertTrue("The cyclic list and array must be sent with their cycles omitted",
                json.contains("[\"<omitted circular reference>\"]"));
        assertTrue(json.contains("A cyclic map:"));
        assertTrue(json.contains("A cyclic list:"));
        assertTrue(json.contains("A cyclic array:"));
    }

    @Test
    public void testUnserializableArgumentIsOmittedWithAMarker() throws Exception {
        log("Log line with a broken argument: {}", new Unserializable());
        log("Log line after the broken one");

        String json = appender.batchToJson(2);

        assertTrue("The broken log line itself must be sent", json.contains("Log line with a broken argument:"));
        assertTrue("Log line after the broken one must be sent", json.contains("Log line after the broken one"));
        assertTrue("The broken argument must be replaced with a marker naming its class",
                json.contains("\"<omitted unserializable " + Unserializable.class.getName() + ">\""));
    }

    @Test
    public void testRepeatedReferencesAreNotMistakenForCycles() throws Exception {
        Map<String, Object> shared = Collections.singletonMap("value", 42);
        Map<String, Object> argument = new HashMap<>();
        argument.put("first", shared);
        argument.put("second", shared);

        log("Same object referenced twice: {}", argument);

        String json = appender.batchToJson(1);

        assertFalse("A repeated reference is not a cycle and must not be omitted", json.contains("omitted"));
        assertTrue(json.contains("\"first\":{\"value\":42}"));
        assertTrue(json.contains("\"second\":{\"value\":42}"));
    }

    private void log(String message, Object... args) {
        appender.append(new LoggingEvent(Logger.FQCN, logger, Level.INFO, message, null, args));
    }
}
