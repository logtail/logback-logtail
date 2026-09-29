package com.logtail.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

/**
 * A batch or queue size below 1, a negative number of retries and a negative timeout would each keep the appender from
 * sending anything. Such a value is ignored with a warning in logback's status, and the appender keeps the value it had
 * and sends with it. 0 retries, which means none, is still valid.
 */
public class LogtailAppenderOutOfRangeSettingsTest {

    @Test
    public void testOutOfRangeValuesInTheConfigKeepTheDefaultsWithAWarning() throws Exception {
        List<String> sentBy = new CopyOnWriteArrayList<>();
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", exchange -> {
            for (Map<String, Object> line : new ObjectMapper().readValue(exchange.getRequestBody(), new TypeReference<List<Map<String, Object>>>() {}))
                sentBy.add((String) line.get("app"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        endpoint.start();
        LoggerContext context = new LoggerContext();
        context.putProperty("ENDPOINT", "http://127.0.0.1:" + endpoint.getAddress().getPort());
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(LogtailAppenderOutOfRangeSettingsTest.class.getResource("/logback-out-of-range-settings.xml"));

            assertEquals("Spring Boot refuses to start on a logback error", Collections.emptyList(),
                    context.getStatusManager().getCopyOfStatusList().stream()
                            .filter(status -> status.getLevel() == Status.ERROR)
                            .map(Status::toString)
                            .collect(Collectors.toList()));
            assertEquals(Arrays.asList(
                    "batchSize must be positive, keeping 1000 instead of 0",
                    "maxQueueSize must be positive, keeping 100000 instead of 0",
                    "maxRetries must be 0 (no retries) or more, keeping 5 instead of -1",
                    "connectTimeout must be 0 (no timeout) or more, keeping 5000 ms instead of -1",
                    "readTimeout must be 0 (no timeout) or more, keeping 10000 ms instead of -1"), warnings(context));
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            assertEquals(1000, ((LogtailAppender) root.getAppender("BatchSize")).batchSize);
            assertEquals(100000, ((LogtailAppender) root.getAppender("MaxQueueSize")).maxQueueSize);
            assertEquals(5, ((LogtailAppender) root.getAppender("MaxRetries")).maxRetries);
            assertEquals(5000, ((LogtailAppender) root.getAppender("ConnectTimeout")).connectTimeout);
            assertEquals(10000, ((LogtailAppender) root.getAppender("ReadTimeout")).readTimeout);

            root.info("Sent by every appender when logback stops");
            context.stop();

            assertEquals(Arrays.asList("BatchSize", "ConnectTimeout", "MaxQueueSize", "MaxRetries", "ReadTimeout"),
                    sentBy.stream().sorted().collect(Collectors.toList()));
        } finally {
            context.stop();
            endpoint.stop(0);
        }
    }

    @Test
    public void testABatchSizeBelow1KeepsTheSizeSetBefore() {
        SendingAppender appender = new SendingAppender();
        appender.setBatchSize(2);
        appender.setBatchSize(-1);

        assertEquals(2, appender.getBatchSize());
        assertEquals(Collections.singletonList("batchSize must be positive, keeping 2 instead of -1"),
                warnings(appender.getContext()));

        appender.start();
        appender.doAppend(event("First of a full batch"));
        appender.doAppend(event("Second of a full batch"));
        appender.doAppend(event("Sent by stop()"));
        appender.stop();

        assertEquals(Arrays.asList(2, 1), appender.sentBatchSizes);
    }

    @Test
    public void testAMaxQueueSizeBelow1KeepsTheSizeSetBefore() {
        SendingAppender appender = new SendingAppender();
        appender.setMaxQueueSize(2);
        appender.setMaxQueueSize(-1);

        assertEquals(2, appender.maxQueueSize);
        assertEquals(Collections.singletonList("maxQueueSize must be positive, keeping 2 instead of -1"),
                warnings(appender.getContext()));

        appender.start();
        appender.doAppend(event("Queued"));
        appender.doAppend(event("Queued as well"));
        appender.doAppend(event("Dropped, the queue is full"));
        appender.stop();

        assertEquals(Collections.singletonList(2), appender.sentBatchSizes);
    }

    @Test
    public void testANegativeMaxRetriesKeepsTheNumberSetBefore() {
        SendingAppender appender = new SendingAppender();
        appender.setMaxRetries(0);
        appender.setMaxRetries(-1);

        assertEquals("0 means no retries", 0, appender.maxRetries);
        assertEquals(Collections.singletonList("maxRetries must be 0 (no retries) or more, keeping 0 instead of -1"),
                warnings(appender.getContext()));

        appender.start();
        appender.doAppend(event("Sent, not dropped before its first attempt"));
        appender.stop();

        assertEquals(Collections.singletonList(1), appender.sentBatchSizes);
    }

    /**
     * Keeps the size of each batch it is asked to send instead of sending it.
     */
    private static class SendingAppender extends LogtailAppender {
        final List<Integer> sentBatchSizes = new CopyOnWriteArrayList<>();

        SendingAppender() {
            setContext(new LoggerContext());
            setSourceToken("source-token");
            // Only a full batch and stop() send during a test
            setBatchInterval(60000);
        }

        @Override
        protected LogtailResponse callHttpURLConnection(int flushedSize) {
            sentBatchSizes.add(flushedSize);
            return new LogtailResponse(null, 202);
        }
    }

    private static LoggingEvent event(String message) {
        Logger logger = new LoggerContext().getLogger(Logger.ROOT_LOGGER_NAME);
        return new LoggingEvent(Logger.FQCN, logger, Level.INFO, message, null, new Object[]{});
    }

    private static List<String> warnings(Context context) {
        return context.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() == Status.WARN)
                .map(Status::getMessage)
                .collect(Collectors.toList());
    }
}
