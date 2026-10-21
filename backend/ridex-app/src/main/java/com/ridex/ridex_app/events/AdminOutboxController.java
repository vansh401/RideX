package com.ridex.ridex_app.events;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.kafka.OutboxStore;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Each service has its own outbox; this is the core's. (The audit log, lag and dead letters live in the analytics service.) */
@RestController
@RequestMapping("/api/admin/outbox")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: events", description = "The core's outbox")
public class AdminOutboxController {

    private final OutboxStore outbox;

    public AdminOutboxController(OutboxStore outbox) {
        this.outbox = outbox;
    }

    @GetMapping
    @Operation(summary = "Events saved but not yet published to Kafka", description = "Normally 0 or very small.")
    public Map<String, Long> pending() {
        return Map.of("pending", outbox.countUnpublished());
    }
}
