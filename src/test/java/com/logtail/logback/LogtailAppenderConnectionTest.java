package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;

/**
 * Batches go out over one kept-alive connection rather than a new TCP (and against the real endpoint, TLS) connection
 * each, also after the endpoint answered with an error.
 */
public class LogtailAppenderConnectionTest {

    @Test
    public void testBatchesReuseOneConnection() throws Exception {
        List<Integer> clientPorts = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream requestBody = exchange.getRequestBody()) {
                byte[] buffer = new byte[1024];
                while (requestBody.read(buffer) != -1) {
                    // Discarded
                }
            }
            clientPorts.add(exchange.getRemoteAddress().getPort());
            boolean first = clientPorts.size() == 1;
            byte[] responseBody = (first ? "Try again" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(first ? 503 : 202, responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        });
        server.start();
        try {
            LoggerContext context = new LoggerContext();
            LogtailAppender appender = new LogtailAppender();
            appender.setContext(context);
            appender.setSourceToken("source-token");
            appender.setIngestUrl("http://127.0.0.1:" + server.getAddress().getPort());
            appender.start();
            Logger logger = context.getLogger("ConnectionTest");
            logger.addAppender(appender);

            for (String message : Arrays.asList("First batch", "Second batch", "Third batch")) {
                logger.info(message);
                appender.flush();
            }
        } finally {
            server.stop(0);
        }

        assertEquals("The first batch is retried after the 503", 4, clientPorts.size());
        assertEquals("All requests came over one connection", 1, new HashSet<>(clientPorts).size());
    }
}
