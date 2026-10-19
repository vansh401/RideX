package com.ridex.ridex_app.rating;

import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.ride.ActorRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One person's rating of another after a completed ride. It refers to the ride and the two people
 * by id only (no object links), so the rating module stays loosely coupled to the rest.
 */
@Entity
@Table(name = "ratings")
public class Rating {

    @Id
    private UUID id;

    @Column(name = "ride_id", nullable = false, updatable = false)
    private UUID rideId;

    @Column(name = "rater_id", nullable = false, updatable = false)
    private UUID raterId;

    @Column(name = "ratee_id", nullable = false, updatable = false)
    private UUID rateeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rater_role", nullable = false, updatable = false)
    private ActorRole raterRole;

    @Column(nullable = false, updatable = false)
    private int stars;

    @Column(updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Required by JPA; not for application code
    protected Rating() {
    }

    public Rating(UUID rideId, UUID raterId, UUID rateeId, ActorRole raterRole, int stars, String comment, Instant now) {
        this.id = UUID.randomUUID();
        this.rideId = rideId;
        this.raterId = raterId;
        this.rateeId = rateeId;
        this.raterRole = raterRole;
        this.stars = stars;
        this.comment = comment == null || comment.isBlank() ? null : comment.trim();
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getRideId() { return rideId; }
    public UUID getRaterId() { return raterId; }
    public UUID getRateeId() { return rateeId; }
    public ActorRole getRaterRole() { return raterRole; }
    public int getStars() { return stars; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
