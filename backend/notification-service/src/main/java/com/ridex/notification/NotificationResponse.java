package com.ridex.notification;

import java.time.Instant;
import java.util.UUID;

/** What the browser sees: the same shape in the list, and in the live push. */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String body,
        UUID rideId,
        Instant createdAt,
        boolean read
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getRideId(),
                n.getCreatedAt(), n.getReadAt() != null);
    }
}
