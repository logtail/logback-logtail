package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertTrue;

/**
 * Reproduction of T-19629 / logtail/logback-logtail#26.
 *
 * A single log line carrying an argument with a cyclic object graph (e.g. a JDBC Connection,
 * which HikariCP logs on every pooled connection creation) makes the Jackson serialization of
 * the whole batch throw, so every other log line in that batch is dropped as well.
 */
public class LogtailAppenderCyclicArgumentTest {

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

        @Override
        public String toString() {
            return "Unserializable value";
        }
    }

    @Test
    public void testCyclicArgumentDoesNotDropTheBatch() throws Exception {
        Parent parent = new Parent();
        Child child = new Child();
        parent.child = child;
        child.parent = parent; // the cycle

        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);

        LogtailAppender appender = new LogtailAppender();
        appender.setAppName("BetterStackTest");
        appender.setSourceToken("dummy-token"); // any non-empty value; no request is made by this test

        appender.append(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Log line before the cyclic one",
                null, new Object[]{}));
        // Equivalent to HikariPool's: LOGGER.debug("{} - Added connection {}", poolName, poolEntry.connection);
        appender.append(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Some object graph: {}",
                null, new Object[]{parent}));
        appender.append(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Log line after the cyclic one: {}",
                null, new Object[]{Collections.singletonMap("orderId", 42)}));

        String json = appender.batchToJson(3);

        assertTrue("Log line before the cyclic one must be sent", json.contains("Log line before the cyclic one"));
        assertTrue("The cyclic log line itself must be sent", json.contains("Some object graph:"));
        assertTrue("Log line after the cyclic one must be sent", json.contains("Log line after the cyclic one:"));

        assertTrue("The cyclic argument must be sent as its string representation",
                json.contains(Parent.class.getName() + "@"));
        assertTrue("Serializable arguments of other log lines must stay structured",
                json.contains("{\"orderId\":42}"));
    }

    @Test
    public void testUnserializableArgumentDoesNotDropTheBatch() throws Exception {
        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);

        LogtailAppender appender = new LogtailAppender();
        appender.setAppName("BetterStackTest");
        appender.setSourceToken("dummy-token");

        appender.append(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Log line with a broken argument: {}",
                null, new Object[]{new Unserializable()}));
        appender.append(new LoggingEvent(Logger.FQCN, logger, Level.INFO, "Log line after the broken one",
                null, new Object[]{}));

        String json = appender.batchToJson(2);

        assertTrue("The broken log line itself must be sent", json.contains("Log line with a broken argument:"));
        assertTrue("Log line after the broken one must be sent", json.contains("Log line after the broken one"));
        assertTrue("The broken argument must be sent as its string representation",
                json.contains("Unserializable value"));
    }
}
