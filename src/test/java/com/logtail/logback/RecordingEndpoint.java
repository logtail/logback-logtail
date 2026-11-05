package com.logtail.logback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stands in for the ingesting endpoint inside the test JVM, so that no test sends to Better Stack: an HTTP server on an
 * ephemeral port that answers every batch with 202 and keeps the Authorization header and the lines of each request.
 * Delivery to the real endpoint is the E2E workflow's job.
 */
public class RecordingEndpoint implements AutoCloseable {

    public static class Request {
        public final String authorization;
        public final List<Map<String, Object>> lines;

        Request(String authorization, List<Map<String, Object>> lines) {
            this.authorization = authorization;
            this.lines = lines;
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();

    public RecordingEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            List<Map<String, Object>> lines = new ObjectMapper().readValue(readAll(exchange.getRequestBody()), new TypeReference<List<Map<String, Object>>>() {});
            synchronized (requests) {
                requests.add(new Request(exchange.getRequestHeaders().getFirst("Authorization"), lines));
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    public List<Integer> batchSizes() {
        return requests().stream().map(request -> request.lines.size()).collect(Collectors.toList());
    }

    public List<Object> messages() {
        return requests().stream().flatMap(request -> request.lines.stream()).map(line -> line.get("message")).collect(Collectors.toList());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream body = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = body.read(buffer); read != -1; read = body.read(buffer))
                out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
