package com.ridex.ridex_app.support;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * A real STOMP-over-WebSocket client for tests, like the browser one. It connects to the running
 * application, collects every message per destination, and reports whether the server accepted or refused it.
 */
public class StompTestClient implements AutoCloseable {

    private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    private final Map<String, BlockingQueue<String>> received = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> connected = new CompletableFuture<>();
    private final CompletableFuture<String> failure = new CompletableFuture<>();
    private volatile StompSession session;

    private StompTestClient() {
        // The server labels messages application/json; accept whatever content type arrives and hand over the text
        client.setMessageConverter(new StringMessageConverter() {
            @Override
            protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) {
                return true;
            }
        });
        // Receipts (used to know a subscription is registered) and heartbeats need a scheduler
        client.setTaskScheduler(new org.springframework.scheduling.concurrent.ConcurrentTaskScheduler());
    }

    /** Opens a connection sending {@code bearerToken} (may be null) in the CONNECT frame. */
    public static StompTestClient connect(int port, String bearerToken) {
        StompTestClient test = new StompTestClient();
        StompHeaders headers = new StompHeaders();
        if (bearerToken != null) {
            headers.add("Authorization", "Bearer " + bearerToken);
        }
        test.client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession connectedSession, StompHeaders connectedHeaders) {
                        test.session = connectedSession;
                        test.connected.complete(null);
                    }

                    @Override
                    public void handleFrame(StompHeaders frameHeaders, Object payload) {
                        // An ERROR frame from the server (refused message, bad token...)
                        test.failure.complete(String.valueOf(frameHeaders.getFirst("message")));
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        test.failure.complete("transport: " + exception.getClass().getSimpleName());
                    }
                });
        return test;
    }

    /** True if the server accepted the connection within the time. */
    public boolean awaitConnected(long seconds) {
        try {
            connected.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True if the server refused or dropped us (ERROR frame or closed connection) within the time. */
    public boolean awaitRefused(long seconds) {
        try {
            failure.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String failureMessage() {
        return failure.getNow(null);
    }

    public void subscribe(String destination) {
        BlockingQueue<String> queue = received.computeIfAbsent(destination, d -> new LinkedBlockingQueue<>());
        session.setAutoReceipt(true);
        CountDownLatch registered = new CountDownLatch(1);
        StompSession.Subscription subscription = session.subscribe(destination, new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders h) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders h, Object payload) {
                queue.add((String) payload);
            }
        });
        subscription.addReceiptTask(registered::countDown);
        try {
            registered.await(5, TimeUnit.SECONDS);
            // The receipt means the frame was accepted; give the in-memory broker a moment to register it
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the next message on a destination, or returns null if none arrives in time. */
    public String next(String destination, long seconds) {
        try {
            return received.computeIfAbsent(destination, d -> new LinkedBlockingQueue<>()).poll(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void send(String destination, String json) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        // Say it is JSON, like the browser client does, so the server converts it to a LatLng
        headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, json);
    }

    @Override
    public void close() {
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } catch (RuntimeException alreadyClosed) {
            // the server may have closed this connection already (that is what several tests check)
        }
        client.stop();
    }
}
