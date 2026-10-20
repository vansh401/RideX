package com.ridex.ridex_app.notification;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.realtime.RealtimePublisher;

/**
 * Persisted AND live notifications.
 *
 * - Persisted: a row in PostgreSQL, so it is still there after a page reload, or if the person was offline.
 * - Live: also pushed on /user/queue/notifications, so an open browser shows it immediately.
 *
 * {@link #notify} is meant to be called AFTER the business transaction committed (from an after-commit
 * listener). It then stores the notification in a NEW transaction of its own; a notification problem must
 * never roll back, or fail, the ride or approval that caused it.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final RealtimePublisher publisher;
    private final TransactionTemplate newTransaction;

    public NotificationService(NotificationRepository repository, RealtimePublisher publisher,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.publisher = publisher;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Stores the notification and pushes it live. Never throws. */
    public void notify(UUID userId, NotificationType type, String title, String body, UUID rideId) {
        try {
            Notification saved = newTransaction.execute(status ->
                    repository.save(new Notification(userId, type, title, body, rideId, Instant.now())));
            publisher.toUser(userId, "/queue/notifications", NotificationResponse.from(saved));
        } catch (RuntimeException e) {
            log.warn("Could not create a notification for user {}: {}", userId, e.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, int page, int size) {
        Pageable pageable = Paging.of(page, size, org.springframework.data.domain.Sort.unsorted());
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
