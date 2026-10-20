package com.ridex.ridex_app.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message for one person ("Your driver has arrived"). It refers to the user and the ride by id only,
 * so the notification module stays independent of the rest.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationType type;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "ride_id", updatable = false)
    private UUID rideId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    // Required by JPA; not for application code
    protected Notification() {
    }

    public Notification(UUID userId, NotificationType type, String title, String body, UUID rideId, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.rideId = rideId;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public UUID getRideId() { return rideId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
}
