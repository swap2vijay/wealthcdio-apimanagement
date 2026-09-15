package com.natwest.ledger.infrastructure.compliance;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * A controllable stand-in for the compliance service, backed by the JDK's own HTTP server.
 *
 * <p>A real socket rather than a mocked client, because the behaviour under test is what happens to
 * connection failures, slow responses and 5xx replies - none of which a stubbed interface can produce
 * faithfully. Using {@code com.sun.net.httpserver} keeps that realism without adding a test dependency.
 *
 * <p>Counting requests is what makes the circuit breaker assertions meaningful: the proof that an open
 * circuit works is not that an error was returned, but that <em>no request was sent at all</em>.
 */
final class StubComplianceService implements AutoCloseable {

    /** One scripted reply. */
    record Reply(int status, String body, Duration delay) {

        static Reply ok(String body) {
            return new Reply(200, body, Duration.ZERO);
        }

        static Reply status(int status) {
            return new Reply(status, "{\"error\":\"stubbed\"}", Duration.ZERO);
        }

        static Reply slow(String body, Duration delay) {
            return new Reply(200, body, delay);
        }
    }

    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();

    /** Maps the 1-based request number to the reply it should receive. */
    private volatile IntFunction<Reply> script = attempt -> Reply.ok(approved("REF-1"));

    private volatile String lastRequestBody;
    private volatile Map<String, List<String>> lastRequestHeaders = Map.of();

    StubComplianceService() {
        try {
            // Port 0 lets the OS choose a free port, so parallel test runs cannot collide.
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the stub compliance service", e);
        }

        server.createContext("/api/v1/screenings", exchange -> {
            int attempt = requestCount.incrementAndGet();
            lastRequestHeaders = Map.copyOf(exchange.getRequestHeaders());
            try (InputStream body = exchange.getRequestBody()) {
                lastRequestBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            }

            Reply reply = script.apply(attempt);
            if (reply.delay() != null && !reply.delay().isZero()) {
                try {
                    Thread.sleep(reply.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respondWith(IntFunction<Reply> script) {
        this.script = script;
    }

    void alwaysRespondWith(Reply reply) {
        this.script = attempt -> reply;
    }

    int requestCount() {
        return requestCount.get();
    }

    String lastRequestBody() {
        return lastRequestBody;
    }

    /**
     * The first value of a header on the most recent request, or null.
     *
     * <p>Header names are matched case-insensitively, as HTTP requires - {@code com.sun.net.httpserver}
     * canonicalises them to {@code Title-Case}, so an exact-match lookup would be quietly wrong.
     */
    String lastRequestHeader(String name) {
        return lastRequestHeaders.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }

    static String approved(String reference) {
        return """
                {"reference":"%s","decision":"APPROVED","reason":null,"screenedAt":"2026-04-20T10:15:30Z"}
                """.formatted(reference);
    }

    static String rejected(String reference, String reason) {
        return """
                {"reference":"%s","decision":"REJECTED","reason":"%s","screenedAt":"2026-04-20T10:15:30Z"}
                """.formatted(reference, reason);
    }

    static String withDecision(String reference, String decision) {
        return """
                {"reference":"%s","decision":"%s","reason":"whatever","screenedAt":"2026-04-20T10:15:30Z"}
                """.formatted(reference, decision);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
