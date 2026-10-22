package com.ridex.ridex_app.safety;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.BadRequestException;
import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.ride.Ride;
import com.ridex.ridex_app.ride.RideStatus;

/**
 * Messages between the rider and the driver of ONE ride. Only those two people can read or write them, and only once a driver has accepted
 * (before that there is nobody to talk to; after the trip a short while remains for "I left my phone in the car"). Plain text, 1 to 500
 * characters, at most 300 messages per ride. Messages are never shown to anyone else, including in the shared trip link.
 */
@Service
public class RideChatService {

    private static final Set<RideStatus> OPEN_FOR_CHAT = Set.of(RideStatus.DRIVER_ACCEPTED, RideStatus.DRIVER_ARRIVED, RideStatus.RIDE_STARTED, RideStatus.RIDE_COMPLETED);
    private static final int MAX_PER_RIDE = 300;

    public record Message(UUID id, String senderRole, String body, Instant createdAt, boolean mine) {
    }

    private final JdbcTemplate jdbc;
    private final SafetyService safety;

    public RideChatService(JdbcTemplate jdbc, SafetyService safety) {
        this.jdbc = jdbc;
        this.safety = safety;
    }

    @Transactional(readOnly = true)
    public List<Message> list(UUID userId, ActorRole role, UUID rideId) {
        safety.participantRide(userId, role, rideId);
        return jdbc.query("select id, sender_id, sender_role, body, created_at from ride_messages where ride_id = ? order by created_at limit ?",
                (rs, n) -> new Message(rs.getObject("id", UUID.class), rs.getString("sender_role"), rs.getString("body"), rs.getTimestamp("created_at").toInstant(),
                        userId.equals(rs.getObject("sender_id", UUID.class))), rideId, MAX_PER_RIDE);
    }

    @Transactional
    public Message send(UUID userId, ActorRole role, UUID rideId, String body) {
        Ride ride = safety.participantRide(userId, role, rideId);
        if (!OPEN_FOR_CHAT.contains(ride.getStatus())) {
            throw new ConflictException("You can chat once a driver has accepted the ride");
        }
        String text = body == null ? "" : body.trim();
        if (text.isEmpty() || text.length() > 500) {
            throw new BadRequestException("A message is 1 to 500 characters");
        }
        Integer count = jdbc.queryForObject("select count(*) from ride_messages where ride_id = ?", Integer.class, rideId);
        if (count != null && count >= MAX_PER_RIDE) {
            throw new ConflictException("This conversation is full");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into ride_messages (id, ride_id, sender_id, sender_role, body, created_at) values (?, ?, ?, ?, ?, ?)", id, rideId, userId, role.name(), text,
                java.sql.Timestamp.from(now));
        return new Message(id, role.name(), text, now, true);
    }
}
