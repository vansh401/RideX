package com.ridex.ridex_app.realtime;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.security.AccessTokenRevocations;
import com.ridex.ridex_app.user.Role;

/**
 * The gatekeeper for everything a browser sends over the WebSocket. Plain HTTP requests are protected by
 * Spring Security's filter chain; a WebSocket is different: it is ONE long-lived connection carrying many
 * messages, so each kind of message has to be checked on its own.
 *
 * 1. CONNECT: a browser cannot add an Authorization header to the WebSocket handshake itself, so the JWT
 *    travels in the header of the first STOMP frame instead. We verify it exactly like the HTTP filter does
 *    (signature, expiry, issuer, and the Redis revocation list). No valid token: no connection.
 * 2. SUBSCRIBE: allowed only for the caller's OWN queues, or for the location topic of a ride the caller
 *    takes part in. Without this check anyone could listen to any ride just by guessing its id.
 * 3. SEND: allowed only to the one destination drivers use to stream their location.
 * 4. Every frame after CONNECT re-checks that the token has not expired or been revoked, so suspending a
 *    user or logging out stops an open socket the next time it speaks.
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompSecurityInterceptor.class);
    private static final String BEARER = "Bearer ";

    /** Queues every signed-in user may subscribe to (each is automatically private to that user). */
    private static final Set<String> USER_QUEUES = Set.of(
            "/user/queue/ride-updates", "/user/queue/offers", "/user/queue/notifications", "/user/queue/errors");

    private static final Pattern RIDE_LOCATION_TOPIC =
            Pattern.compile("^/topic/rides/([0-9a-fA-F-]{36})/location$");

    public static final String DRIVER_LOCATION_DESTINATION = "/app/driver/location";

    private final JwtDecoder jwtDecoder;
    private final AccessTokenRevocations revocations;
    private final RideRepository rideRepository;

    public StompSecurityInterceptor(JwtDecoder jwtDecoder, AccessTokenRevocations revocations, RideRepository rideRepository) {
        this.jwtDecoder = jwtDecoder;
        this.revocations = revocations;
        this.rideRepository = rideRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(stillValid(accessor), accessor.getDestination());
        } else if (StompCommand.SEND.equals(command)) {
            authorizeSend(stillValid(accessor), accessor.getDestination());
        }
        return message;
    }

    private StompPrincipal authenticate(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            throw new AccessDeniedException("Authentication required: send the access token in the CONNECT frame");
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(BEARER.length()).trim());
            UUID userId = UUID.fromString(jwt.getSubject());
            Role role = Role.valueOf(jwt.getClaimAsString("role"));
            Long precise = jwt.getClaim("iat_ms");
            long issuedAt = precise != null ? precise : jwt.getIssuedAt().toEpochMilli();
            if (revocations.isRevoked(userId, jwt.getId(), issuedAt)) {
                throw new AccessDeniedException("This token has been revoked");
            }
            return new StompPrincipal(userId, role, jwt.getId(), issuedAt, jwt.getExpiresAt());
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            log.debug("Rejected WebSocket connection: {}", e.getClass().getSimpleName());
            throw new AccessDeniedException("Invalid or expired access token");
        }
    }

    /** The authenticated principal of this connection, after checking its token is still good. */
    private StompPrincipal stillValid(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            throw new AccessDeniedException("Not authenticated");
        }
        if (principal.tokenExpiresAt() != null && principal.tokenExpiresAt().isBefore(Instant.now())) {
            throw new AccessDeniedException("The access token of this connection has expired. Reconnect with a fresh one");
        }
        if (revocations.isRevoked(principal.userId(), principal.tokenId(), principal.issuedAtMillis())) {
            throw new AccessDeniedException("This token has been revoked");
        }
        return principal;
    }

    private void authorizeSubscribe(StompPrincipal principal, String destination) {
        if (destination == null) {
            throw new AccessDeniedException("A subscription needs a destination");
        }
        if (USER_QUEUES.contains(destination)) {
            return;
        }
        Matcher ride = RIDE_LOCATION_TOPIC.matcher(destination);
        if (ride.matches()) {
            UUID rideId = UUID.fromString(ride.group(1));
            if (principal.role() == Role.ADMIN || rideRepository.isParticipant(rideId, principal.userId())) {
                return;
            }
        }
        // The same answer whether the ride does not exist or belongs to somebody else: ids cannot be probed
        throw new AccessDeniedException("You may not subscribe to " + destination);
    }

    private void authorizeSend(StompPrincipal principal, String destination) {
        if (DRIVER_LOCATION_DESTINATION.equals(destination) && principal.role() == Role.DRIVER) {
            return;
        }
        throw new AccessDeniedException("You may not send to " + destination);
    }
}
