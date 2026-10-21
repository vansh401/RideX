package com.ridex.notification;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.platform.realtime.RealtimePush;
import com.ridex.platform.tx.AfterCommit;
import com.ridex.platform.web.PageResponse;
import com.ridex.platform.web.Paging;
import com.ridex.platform.web.ResourceNotFoundException;

/**
 * Persisted AND live notifications.
 *
 * - Persisted: a row in this service's own database, so it is still there after a page reload, or if the person was
 *   offline.
 * - Live: also pushed to the person's open browser. This service has no WebSocket server; it publishes to a Redis channel
 *   that the core application's WebSocket servers listen to.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final RealtimePush push;

    public NotificationService(NotificationRepository repository, RealtimePush push) {
        this.repository = repository;
        this.push = push;
    }

    /**
     * Saves a notification as part of the caller's own transaction and pushes it live once that transaction commits.
     * Used by the Kafka consumer, where the notification and the "event already handled" marker must commit together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID userId, NotificationType type, String title, String body, UUID rideId) {
        Notification saved = repository.save(new Notification(userId, type, title, body, rideId, Instant.now()));
        NotificationResponse response = NotificationResponse.from(saved);
        AfterCommit.run(() -> pushLive(userId, response));
    }

    private void pushLive(UUID userId, NotificationResponse response) {
        try {
            push.toUser(userId, "/queue/notifications", response);
        } catch (RuntimeException e) {
            log.warn("Could not push a notification to user {}: {}", userId, e.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.unsorted());
        return PageResponse.from(repository.findByUserIdOrderByCreatedAtDesc(userId, pageable), NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        // 404 for somebody else's notification, exactly like one that does not exist
        if (!repository.existsByIdAndUserId(notificationId, userId)) {
            throw new ResourceNotFoundException("Notification not found");
        }
        repository.markRead(notificationId, userId, Instant.now());   // already read: nothing to do, still fine
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repository.markAllRead(userId, Instant.now());
    }
}
