package com.ridex.ridex_app.notification;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The logged-in user's own notifications. Anyone signed in; nobody can see anyone else's. */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "In-app notifications (stored here, and also pushed live over the WebSocket)")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "My notifications, newest first")
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return service.list(principal.id(), page, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many notifications have I not read yet?")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Map.of("count", service.unreadCount(principal.id()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one notification as read", description = "404 if it is not yours.")
    public void markRead(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        service.markRead(principal.id(), id);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Map.of("updated", service.markAllRead(principal.id()));
    }
}
