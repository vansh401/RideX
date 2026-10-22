package com.ridex.ridex_app.complaint;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A message on a complaint. {@code internal} ones are notes between administrators: the person who complained never sees them. */
@Entity
@Table(name = "complaint_comments")
public class ComplaintComment {

    @Id
    private UUID id;
    @Column(name = "complaint_id", nullable = false, updatable = false)
    private UUID complaintId;
    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;
    @Column(name = "author_role", nullable = false, updatable = false)
    private String authorRole;
    @Column(nullable = false, updatable = false)
    private String message;
    @Column(nullable = false, updatable = false)
    private boolean internal;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ComplaintComment() {
    }

    ComplaintComment(UUID complaintId, UUID authorId, String authorRole, String message, boolean internal, Instant now) {
        this.id = UUID.randomUUID();
        this.complaintId = complaintId;
        this.authorId = authorId;
        this.authorRole = authorRole;
        this.message = message.trim();
        this.internal = internal;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorRole() { return authorRole; }
    public String getMessage() { return message; }
    public boolean isInternal() { return internal; }
    public Instant getCreatedAt() { return createdAt; }
}
