package com.ridex.ridex_app.realtime;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** WebSocket settings (application.properties, prefix ridex.websocket). */
@ConfigurationProperties(prefix = "ridex.websocket")
public record RealtimeProperties(
        /** Pages allowed to open a WebSocket. A page from any other site is refused. */
        List<String> allowedOrigins,
        /** How often client and server send a "still here" signal, in milliseconds. */
        long heartbeatMs,
        /** A driver may send at most this many location updates per 10 seconds; extra ones are ignored. */
        int locationUpdatesPer10Seconds
) {
}
