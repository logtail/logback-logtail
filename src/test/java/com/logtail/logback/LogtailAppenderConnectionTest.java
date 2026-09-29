package com.logtail.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Batches go out over one kept-alive connection rather than a new TCP (and against the real endpoint, TLS) connection
 * each, also after the endpoint answered with an error, and a kept connection that died while idle costs no batch
 * and sends none twice. Neither does an answer that cannot be read to its end.
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
            sendBatches("http://127.0.0.1:" + server.getAddress().getPort(), 10000, "First batch", "Second batch", "Third batch");
        } finally {
            server.stop(0);
        }

        assertEquals("The first batch is retried after the 503", 4, clientPorts.size());
        assertEquals("All requests came over one connection", 1, new HashSet<>(clientPorts).size());
    }

    @Test
    public void testABatchGoesOutOnceWhenTheServerClosedTheKeptConnection() throws Exception {
        // Like a load balancer closing an idle connection: HttpURLConnection finds out when it reuses the connection
        try (RawHttpEndpoint endpoint = new RawHttpEndpoint(RawHttpEndpoint.AfterAnswer.CLOSE)) {
            LogtailAppenderDecorator appender = sendBatches(endpoint.url(), 10000, "First batch", "Second batch", "Third batch");

            assertEquals(Arrays.asList("First batch", "Second batch", "Third batch"), endpoint.messages);
            assertEquals("No batch needed the appender's own retry", 3, appender.apiCalls);
            assertFalse(appender.hasException());
        }
    }

    @Test
    public void testABatchGoesOutOnceWhenTheServerResetsTheKeptConnectionAsTheBatchArrives() throws Exception {
        // The batch races the server closing the connection. HttpURLConnection resends it on a new connection,
        // which it only does for a buffered body - a streamed one would fail and wait for the appender's own retry
        try (RawHttpEndpoint endpoint = new RawHttpEndpoint(RawHttpEndpoint.AfterAnswer.RESET_ON_NEXT_REQUEST)) {
            LogtailAppenderDecorator appender = sendBatches(endpoint.url(), 10000, "First batch", "Second batch", "Third batch");

            assertEquals(Arrays.asList("First batch", "Second batch", "Third batch"), endpoint.messages);
            assertEquals("No batch needed the appender's own retry", 3, appender.apiCalls);
            assertFalse(appender.hasException());
        }
    }

    @Test
    public void testABatchGoesOutOnceAfterTheKeptConnectionDiedSilently() throws Exception {
        // Like a firewall dropping an idle connection without telling either side: the batch waits for the read
        // timeout, then the appender's retry sends it over a new connection
        try (RawHttpEndpoint endpoint = new RawHttpEndpoint(RawHttpEndpoint.AfterAnswer.SWALLOW_NEXT_REQUESTS)) {
            LogtailAppenderDecorator appender = sendBatches(endpoint.url(), 500, "First batch", "Second batch");

            assertEquals(Arrays.asList("First batch", "Second batch"), endpoint.messages);
            assertEquals("The second batch is retried once, after its read timeout", 3, appender.apiCalls);
            assertTrue(appender.getException() instanceof SocketTimeoutException);
        }
    }

    @Test
    public void testABatchGoesOutOnceWhenTheRestOfItsAnswerNeverArrives() throws Exception {
        // The 202 says the batch was taken. Not being able to read the answer to its end costs the connection, but
        // the batch must not go out again
        String answerWithoutItsBody = "HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\n\r\n";
        try (RawHttpEndpoint endpoint = new RawHttpEndpoint(answerWithoutItsBody, RawHttpEndpoint.AfterAnswer.SWALLOW_NEXT_REQUESTS)) {
            LogtailAppenderDecorator appender = sendBatches(endpoint.url(), 300, "First batch", "Second batch");

            assertEquals(Arrays.asList("First batch", "Second batch"), endpoint.messages);
            assertEquals("No batch needed the appender's own retry", 2, appender.apiCalls);
            assertFalse(appender.hasException());
        }
    }

    private static LogtailAppenderDecorator sendBatches(String ingestUrl, int readTimeout, String... messages) {
        LoggerContext context = new LoggerContext();
        LogtailAppenderDecorator appender = new LogtailAppenderDecorator();
        appender.setContext(context);
        appender.setSourceToken("source-token");
        appender.setIngestUrl(ingestUrl);
        appender.setReadTimeout(readTimeout);
        appender.start();
        Logger logger = context.getLogger("ConnectionTest");
        logger.addAppender(appender);

        for (String message : messages) {
            logger.info(message);
            appender.flush();
        }
        return appender;
    }

    /**
     * A minimal HTTP/1.1 endpoint on a plain socket, so a test can decide what it answers and what happens to a
     * connection after its first answer. It answers without "Connection: close", so HttpURLConnection keeps every
     * connection for reuse.
     */
    private static class RawHttpEndpoint implements AutoCloseable {
        enum AfterAnswer { CLOSE, RESET_ON_NEXT_REQUEST, SWALLOW_NEXT_REQUESTS }

        final List<Object> messages = new CopyOnWriteArrayList<>();
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final List<Socket> connections = new CopyOnWriteArrayList<>();

        RawHttpEndpoint(AfterAnswer afterAnswer) throws IOException {
            this("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n", afterAnswer);
        }

        RawHttpEndpoint(String answer, AfterAnswer afterAnswer) throws IOException {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket connection = serverSocket.accept();
                        connections.add(connection);
                        Thread handler = new Thread(() -> serve(connection, answer, afterAnswer));
                        handler.setDaemon(true);
                        handler.start();
                    }
                } catch (IOException e) {
                    // close() stopped the endpoint
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String url() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort();
        }

        private void serve(Socket connection, String answer, AfterAnswer afterAnswer) {
            try {
                InputStream in = connection.getInputStream();
                if (readLine(in) == null)
                    return;
                int contentLength = 0;
                for (String header = readLine(in); header != null && !header.isEmpty(); header = readLine(in)) {
                    if (header.toLowerCase().startsWith("content-length:"))
                        contentLength = Integer.parseInt(header.substring("content-length:".length()).trim());
                }
                byte[] body = new byte[contentLength];
                new DataInputStream(in).readFully(body);
                for (Map<String, Object> line : new ObjectMapper().readValue(body, new TypeReference<List<Map<String, Object>>>() {}))
                    messages.add(line.get("message"));

                OutputStream out = connection.getOutputStream();
                out.write(answer.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                switch (afterAnswer) {
                    case CLOSE:
                        connection.close();
                        break;
                    case RESET_ON_NEXT_REQUEST:
                        if (in.read() != -1) {
                            connection.setSoLinger(true, 0);
                            connection.close();
                        }
                        break;
                    case SWALLOW_NEXT_REQUESTS:
                        // Never reads from the connection again, until close()
                        break;
                }
            } catch (IOException e) {
                // The client or close() ended the connection
            }
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            for (int c = in.read(); c != '\n'; c = in.read()) {
                if (c == -1)
                    return null;
                if (c != '\r')
                    line.append((char) c);
            }
            return line.toString();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket connection : connections)
                connection.close();
        }
    }
}
