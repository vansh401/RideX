package com.ridex.ridex_app.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.DriverAvailabilityService;
import com.ridex.ridex_app.ride.RideService;
import com.ridex.ridex_app.security.RateLimiter;

import jakarta.validation.Valid;

/**
 * A driver's app streams its position over the WebSocket: driver app, then this class, then Redis (the
 * location index), then the rider's screen.
 *
 *   driver --SEND /app/driver/location--> here
 *        1. refresh the driver's position and heartbeat in Redis (so matching sees them)
 *        2. if the driver is on a trip, also publish the position to /topic/rides/{id}/location,
 *           where the rider (and only the rider) is subscribed
 *
 * Compared with the REST endpoint (PUT /api/drivers/me/availability/location) there is no new HTTP request,
 * headers or authentication work per update: one open connection, tiny frames.
 */
@Controller
public class DriverLocationStream {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationStream.class);

    private final DriverAvailabilityService availability;
    private final RideService rides;
    private final RealtimePublisher publisher;
    private final RateLimiter limiter;
    private final RealtimeProperties properties;

    public DriverLocationStream(DriverAvailabilityService availability, RideService rides,
                                RealtimePublisher publisher, RateLimiter limiter, RealtimeProperties properties) {
        this.availability = availability;
        this.rides = rides;
        this.publisher = publisher;
        this.limiter = limiter;
        this.properties = properties;
    }

    @MessageMapping("/driver/location")
    public void location(@Payload @Valid LatLng position, StompPrincipal principal) {
        // A misbehaving or buggy client could send hundreds of positions a second. Extra ones are dropped
        // silently (a position that is a second old is not worth an error).
        RateLimiter.Decision decision = limiter.hit("ws-location", principal.getName(),
                properties.locationUpdatesPer10Seconds(), Duration.ofSeconds(10));
        if (!decision.allowed()) {
            return;
        }

        availability.updateLocation(principal.userId(), position);   // Redis; 409 if the driver is not online

        rides.activeRideIdOfDriver(principal.userId()).ifPresent(rideId -> publisher.toTopic(
                "/topic/rides/" + rideId + "/location",
                Map.of("rideId", rideId.toString(),
                        "latitude", position.latitude(),
                        "longitude", position.longitude(),
                        "at", Instant.now().toString())));
    }

    /** A refused or invalid message is answered on the sender's private /user/queue/errors, not broadcast. */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public Map<String, String> onError(Exception e) {
        if (!(e instanceof ConflictException)) {
            log.debug("WebSocket message refused: {}", e.getClass().getSimpleName());
        }
        return Map.of("message", e instanceof ConflictException ? e.getMessage() : "Your message was not accepted");
    }
}
