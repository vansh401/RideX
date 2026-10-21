package com.ridex.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * Stand-ins for the five real services. Each answers every request with a JSON description of what it RECEIVED (service name,
 * method, path, query and the headers the gateway is responsible for), so tests can see exactly what the gateway forwarded.
 */
final class FakeServices {

    static final String[] NAMES = {"core", "notification", "payment", "pricing", "analytics"};

    private static final Map<String, HttpServer> SERVERS = new LinkedHashMap<>();
    private static final Map<String, AtomicInteger> HITS = new LinkedHashMap<>();

    private static final Map<String, Integer> PORTS = new LinkedHashMap<>();

    static {
        for (String name : NAMES) {
            HITS.put(name, new AtomicInteger());
            start(name, 0);
        }
    }

    private static void start(String name, int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            server.createContext("/", exchange -> {
                HITS.get(name).incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                if (exchange.getRequestURI().getPath().endsWith("/slow")) {
                    try {
                        Thread.sleep(3000);   // longer than the gateway's response timeout in the tests
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                var h = exchange.getRequestHeaders();
                String body = "{\"service\":\"" + name + "\",\"method\":\"" + exchange.getRequestMethod() + "\",\"path\":\""
                        + exchange.getRequestURI().getPath() + "\",\"query\":\"" + (exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery())
                        + "\",\"userId\":" + quote(h.getFirst("X-User-Id")) + ",\"role\":" + quote(h.getFirst("X-User-Role"))
                        + ",\"authorization\":" + quote(h.getFirst("Authorization")) + ",\"requestId\":" + quote(h.getFirst("X-Request-Id"))
                        + ",\"forwardedFor\":" + quote(h.getFirst("X-Forwarded-For")) + "}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());   // a slow request must not block the others
            server.start();
            SERVERS.put(name, server);
            PORTS.put(name, server.getAddress().getPort());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Simulates a crashed service: nothing listens on its port any more. */
    static void stop(String name) {
        SERVERS.get(name).stop(0);
    }

    /** Brings it back on the same port. */
    static void restart(String name) {
        start(name, PORTS.get(name));
    }

    private FakeServices() {
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String url(String name) {
        return "http://localhost:" + SERVERS.get(name).getAddress().getPort();
    }

    static int hits(String name) {
        return HITS.get(name).get();
    }

    static int totalHits() {
        return HITS.values().stream().mapToInt(AtomicInteger::get).sum();
    }
}
