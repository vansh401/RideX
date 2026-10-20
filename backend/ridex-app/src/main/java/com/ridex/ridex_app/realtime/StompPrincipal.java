package com.ridex.ridex_app.realtime;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.user.Role;

/**
 * Who is on the other end of a WebSocket, as proven by the JWT sent when the connection was opened.
 * {@link #getName()} is the user id, which is how messages are addressed to a specific user.
 */
public record StompPrincipal(UUID userId, Role role, String tokenId, long issuedAtMillis, Instant tokenExpiresAt)
        implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
