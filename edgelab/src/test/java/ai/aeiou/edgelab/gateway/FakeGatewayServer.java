package ai.aeiou.edgelab.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/** A local stand-in for the 5U gateway: records each request and answers with a canned reply. */
final class FakeGatewayServer implements AutoCloseable {

    record Request(String method, String path, String authorization, String contentType, String body) {}

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    volatile int status = 200;
    volatile String reply = "{}";

    FakeGatewayServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(in, StandardCharsets.ISO_8859_1)));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
    }

    /** Base URL that mirrors the real one's shape: http://127.0.0.1:port/v1/gateway */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/gateway";
    }

    Request last() { return requests.get(requests.size() - 1); }

    @Override
    public void close() {
        server.stop(0);
    }
}
