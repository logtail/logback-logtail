package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;

import static org.junit.Assert.*;

public class Logtail4jIntegrationTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(Logtail4jIntegrationTest.class);

    private Logtail4jDecorator appender;

    @Before
    public void init() throws JoranException {
        LoggerContext loggerContext = ((LoggerContext) LoggerFactory.getILoggerFactory());
        loggerContext.reset();

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(loggerContext);
        configurator.doConfigure("src/test/resources/logtail4j.xml");

        this.appender = new Logtail4jDecorator();
        this.appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        this.appender.setIngestKey("rzAtu7YRvk2XW6qmPZwn4HSY");
        this.appender.start();

        this.logger.addAppender(appender);
    }

    @After
    public void tearDown() {
        this.logger.detachAppender(appender);
    }

    //
    @Test
    public void testInfoLog() {
        MDC.put("requestId", "testInfoLog");
        MDC.put("requestTime", "123");
        this.logger.info("I am Groot");
        this.appender.processEventQueue();

        isOk();
    }

    @Test
    public void testJsonLog() {
        MDC.put("requestId", "testJsonLog");
        MDC.put("requestTime", "456");
        this.logger.info("I am { \"name\": \"Groot\", \"id\": \"GROOT\" }");
        this.appender.processEventQueue();
        isOk();
    }

    @Test
    public void testWarnLog() {
        MDC.put("requestId", "testWarnLog");
        MDC.put("requestTime", "666");
        this.logger.warn("I AM groot");
        this.appender.processEventQueue();
        isOk();
    }

    @Test
    public void testErrorLog() {
        MDC.put("requestId", "testErrorLog");
        MDC.put("requestTime", "789");
        this.logger.error("I am Groot?", new RuntimeException("GROOT!"));
        this.appender.processEventQueue();
        isOk();
    }

    @Test
    public void testConnectTimeout(){
        this.appender.connectionTimeout = 1;
        this.logger.error("I am no Groot");
        this.appender.processEventQueue();
        assertTrue(appender.hasException());
        assertTrue(appender.getException() instanceof IOException);
        //assertNotNull(appender.getException().getCause());
        assertNotNull(appender.getException().getMessage());
        assertEquals("connect timed out", appender.getException().getMessage().toLowerCase());
    }

    @Test
    public void testReadTimeout(){
        this.appender.readTimeout = 1;
        this.logger.error("I am no Groot");
        this.appender.processEventQueue();
        assertTrue(appender.hasException());
        assertTrue(appender.getException() instanceof IOException);
        assertNotNull(appender.getException().getCause());
        assertEquals("Read timed out", appender.getException().getCause().getMessage());
    }

    private void isOk() {
        if (!appender.isOK() && appender.hasError()) {
            System.out.println(appender.getResponse().getResponseCode() + " - " + appender.getResponse().getResponseMessage());
        }
        if (!appender.isOK() && appender.hasException()) {
            appender.getException().printStackTrace();
        }
        assertTrue(appender.isOK());
    }
}
