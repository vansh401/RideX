package com.ridex.ridex_app.complaint;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.platform.contracts.ComplaintEventData;
import com.ridex.platform.contracts.EventEnvelope;
import com.ridex.platform.contracts.Topics;
import com.ridex.platform.kafka.OutboxService;
import com.ridex.ridex_app.common.BadRequestException;
import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.ride.Ride;
import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.user.UserRepository;

/**
 * Complaints: a rider or a driver reports a problem with a ride; an administrator reviews it, talks to them, and closes it, possibly with a refund.
 *
 * WHO MAY COMPLAIN. Only the rider or the driver of THAT ride (anyone else is told the ride does not exist, so ride ids cannot be probed), only if a
 * driver actually took the ride, and only for 14 days after it ended: later, memories and evidence are gone, and a time limit stops old rides from
 * becoming a way to ask for money for ever. One open complaint per person per ride: the second one adds nothing but noise (the database enforces it
 * too, with a partial unique index).
 *
 * WHO SEES WHAT. A person sees their own complaints and the messages meant for them. Internal notes between administrators are never shown to them.
 *
 * REFUNDS ARE EVENTS. This service does not touch money and does not call the payment service. Closing a complaint with a refund saves a
 * "complaint.resolved" event (in the same transaction as the new status, through the outbox); the payment service reacts and refunds. If the
 * payment service is down, the event waits; nothing is lost and nobody is stuck. (Compensation by event, like the ride-payment saga.)
 */
@Service
public class ComplaintService {

    static final Duration WINDOW = Duration.ofDays(14);
    private static final Set<Complaint.Status> OPEN = Set.of(Complaint.Status.OPEN, Complaint.Status.IN_REVIEW);

    public record CommentView(UUID id, String authorRole, String message, boolean internal, Instant createdAt) {
    }

    public record RideSummary(String pickup, String dropoff, BigDecimal fare, String status) {
    }

    public record ComplaintView(UUID id, UUID rideId, Complaint.Category category, String description, Complaint.Status status, String filedByRole,
                                UUID filedBy, String filedByName, UUID assignedTo, String resolutionNote, BigDecimal refundAmount,
                                Instant createdAt, Instant updatedAt, Instant resolvedAt, List<CommentView> comments, RideSummary ride) {
    }

    private final ComplaintRepository complaints;
    private final ComplaintCommentRepository comments;
    private final RideRepository rides;
    private final UserRepository users;
    private final OutboxService outbox;

    public ComplaintService(ComplaintRepository complaints, ComplaintCommentRepository comments, RideRepository rides, UserRepository users,
                            OutboxService outbox) {
        this.complaints = complaints;
        this.comments = comments;
        this.rides = rides;
        this.users = users;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ for riders and drivers

    @Transactional
    public ComplaintView file(UUID userId, ActorRole role, UUID rideId, Complaint.Category category, String description) {
        Ride ride = rides.findDetailedById(rideId)
                .filter(r -> role == ActorRole.RIDER ? r.getRider().getId().equals(userId)
                        : r.getDriver() != null && r.getDriver().getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found"));
        if (ride.getAcceptedAt() == null) {
            throw new ConflictException("A complaint is about a ride that a driver took");
        }
        Instant now = Instant.now();
        Instant ended = ride.getCompletedAt() != null ? ride.getCompletedAt() : ride.getCancelledAt() != null ? ride.getCancelledAt() : now;
        if (ended.plus(WINDOW).isBefore(now)) {
            throw new ConflictException("Complaints can be filed for 14 days after the ride");
        }
        if (complaints.existsByRideIdAndFiledByAndStatusIn(rideId, userId, OPEN)) {
            throw new ConflictException("You already have a complaint about this ride that is being handled");
        }
        Complaint complaint = complaints.saveAndFlush(Complaint.file(rideId, userId, role.name(), category, description, now));
        publish(EventEnvelope.COMPLAINT_FILED, complaint);
        return view(complaint, false, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintView> mine(UUID userId, int page, int size) {
        Page<Complaint> found = complaints.findByFiledBy(userId, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.from(found, c -> view(c, false, false));
    }

    @Transactional(readOnly = true)
    public ComplaintView mine(UUID userId, UUID id) {
        return view(owned(userId, id), false, false);
    }

    /** The person adds a message while their complaint is being handled. */
    @Transactional
    public ComplaintView reply(UUID userId, ActorRole role, UUID id, String message) {
        Complaint complaint = owned(userId, id);
        if (complaint.getStatus().isFinal()) {
            throw new ConflictException("This complaint is closed");
        }
        Instant now = Instant.now();
        comments.save(new ComplaintComment(id, userId, role.name(), message, false, now));
        complaint.touch(now);
        return view(complaint, false, false);
    }

    private Complaint owned(UUID userId, UUID id) {
        return complaints.findById(id).filter(c -> c.getFiledBy().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));
    }

    // ------------------------------------------------------------------ for administrators

    @Transactional(readOnly = true)
    public PageResponse<ComplaintView> list(Complaint.Status status, int page, int size) {
        var pageable = Paging.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));   // oldest first: the longest wait is served first
        Page<Complaint> found = status == null ? complaints.findAll(pageable) : complaints.findByStatus(status, pageable);
        return PageResponse.from(found, c -> view(c, true, false));
    }

    @Transactional(readOnly = true)
    public ComplaintView detail(UUID id) {
        return view(any(id), true, true);
    }

    @Transactional
    public ComplaintView assign(UUID adminId, UUID id) {
        Complaint complaint = any(id);
        complaint.assign(adminId, Instant.now());
        return view(complaint, true, true);
    }

    @Transactional
    public ComplaintView comment(UUID adminId, UUID id, String message, boolean internal) {
        Complaint complaint = any(id);
        Instant now = Instant.now();
        comments.save(new ComplaintComment(id, adminId, "ADMIN", message, internal, now));
        complaint.touch(now);
        return view(complaint, true, true);
    }

    @Transactional
    public ComplaintView resolve(UUID adminId, UUID id, String note, BigDecimal refund) {
        Complaint complaint = any(id);
        if (refund != null) {
            Ride ride = rides.findById(complaint.getRideId()).orElseThrow();
            if (refund.compareTo(ride.getTotalFare()) > 0) {
                throw new BadRequestException("A refund cannot be more than the fare of the ride (" + ride.getTotalFare() + ")");
            }
        }
        complaint.resolve(adminId, note, refund, Instant.now());
        publish(EventEnvelope.COMPLAINT_RESOLVED, complaint);
        return view(complaint, true, true);
    }

    @Transactional
    public ComplaintView reject(UUID adminId, UUID id, String note) {
        Complaint complaint = any(id);
        complaint.reject(adminId, note, Instant.now());
        publish(EventEnvelope.COMPLAINT_RESOLVED, complaint);   // the same event type: it carries the final status, and no refund
        return view(complaint, true, true);
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return complaints.countByStatus(Complaint.Status.OPEN);
    }

    private Complaint any(UUID id) {
        return complaints.findById(id).orElseThrow(() -> new ResourceNotFoundException("Complaint not found"));
    }

    // ------------------------------------------------------------------ helpers

    private void publish(String type, Complaint c) {
        // Saved in THIS transaction with the new status (outbox), published to Kafka afterwards
        outbox.append(Topics.COMPLAINT_EVENTS, c.getRideId().toString(), type, new ComplaintEventData(c.getId(), c.getRideId(), c.getFiledBy(),
                c.getCategory().name(), c.getStatus().name(), c.getRefundAmount(), c.getResolutionNote()));
    }

    private ComplaintView view(Complaint c, boolean admin, boolean withRide) {
        List<CommentView> messages = (admin ? comments.findByComplaintIdOrderByCreatedAt(c.getId()) : comments.findByComplaintIdAndInternalFalseOrderByCreatedAt(c.getId()))
                .stream().map(m -> new CommentView(m.getId(), m.getAuthorRole(), m.getMessage(), m.isInternal(), m.getCreatedAt())).toList();
        String name = admin ? users.findById(c.getFiledBy()).map(u -> u.getFullName()).orElse(null) : null;
        RideSummary ride = null;
        if (withRide) {
            ride = rides.findById(c.getRideId()).map(r -> new RideSummary(r.getPickupAddress(), r.getDropoffAddress(), r.getTotalFare(), r.getStatus().name())).orElse(null);
        }
        return new ComplaintView(c.getId(), c.getRideId(), c.getCategory(), c.getDescription(), c.getStatus(), c.getFiledByRole(),
                admin ? c.getFiledBy() : null, name, admin ? c.getAssignedTo() : null, c.getResolutionNote(), c.getRefundAmount(),
                c.getCreatedAt(), c.getUpdatedAt(), c.getResolvedAt(), messages, ride);
    }
}
