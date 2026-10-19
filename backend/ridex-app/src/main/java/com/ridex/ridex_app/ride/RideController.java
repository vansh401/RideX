package com.ridex.ridex_app.ride;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/rides")
@Tag(name = "Rides", description = "Request a ride and follow it from offer to drop-off")
public class RideController {

    private final RideService service;

    public RideController(RideService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- rider

    @PostMapping
    @PreAuthorize("hasRole('RIDER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a ride",
            description = "Quotes the fare, creates the ride and looks for the nearest available driver. "
                    + "A rider can have only one ride in progress (409 otherwise).")
    public RideResponse request(@AuthenticationPrincipal AuthenticatedUser principal,
                                @Valid @RequestBody RideRequest request) {
        return service.request(principal.id(), request);
    }

    // ---------------------------------------------------------------- reading (rider or driver)

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
    @Operation(summary = "The ride I am currently in", description = "204 when there is none. Poll this to follow a ride.")
    public ResponseEntity<RideResponse> current(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.current(principal.id(), actor(principal))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
    @Operation(summary = "My ride history", description = "Newest first. Optional status filter.")
    public PageResponse<RideResponse> history(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @RequestParam(required = false) RideStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.history(principal.id(), actor(principal), status, page, size);
    }

    @GetMapping("/{rideId}")
    @PreAuthorize("hasAnyRole('RIDER', 'DRIVER', 'ADMIN')")
    @Operation(summary = "One ride", description = "404 unless you are its rider, its driver, or an admin.")
    public RideResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.get(principal.id(), actor(principal), rideId);
    }

    @GetMapping("/{rideId}/timeline")
    @PreAuthorize("hasAnyRole('RIDER', 'DRIVER', 'ADMIN')")
    @Operation(summary = "The audit trail of a ride", description = "Every status change, who made it and when.")
    public List<TimelineEntry> timeline(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.timeline(principal.id(), actor(principal), rideId);
    }

    // ---------------------------------------------------------------- driver

    @PostMapping("/{rideId}/accept")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Accept the ride offered to me", description = "409 if the offer expired or was withdrawn.")
    public RideResponse accept(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.accept(principal.id(), rideId);
    }

    @PostMapping("/{rideId}/reject")
    @PreAuthorize("hasRole('DRIVER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Decline the ride offered to me", description = "It is offered to the next nearest driver.")
    public void reject(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        service.reject(principal.id(), rideId);
    }

    @PostMapping("/{rideId}/arrived")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "I have arrived at the pickup")
    public RideResponse arrived(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.arrived(principal.id(), rideId);
    }

    @PostMapping("/{rideId}/start")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Start the trip", description = "Only after arriving at the pickup.")
    public RideResponse start(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.start(principal.id(), rideId);
    }

    @PostMapping("/{rideId}/complete")
    @PreAuthorize("hasRole('DRIVER')")
    @Operation(summary = "Complete the trip", description = "Only while the trip is in progress.")
    public RideResponse complete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.complete(principal.id(), rideId);
    }

    // ---------------------------------------------------------------- rider or driver

    @PostMapping("/{rideId}/cancel")
    @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
    @Operation(summary = "Cancel the ride",
            description = "Possible until the trip starts. After that, 409. A driver can cancel only after accepting.")
    public RideResponse cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID rideId,
                               @Valid @RequestBody(required = false) CancelRideRequest request) {
        return service.cancel(principal.id(), actor(principal), rideId, request == null ? null : request.reason());
    }

    private static ActorRole actor(AuthenticatedUser principal) {
        return ActorRole.valueOf(principal.role().name());
    }
}
