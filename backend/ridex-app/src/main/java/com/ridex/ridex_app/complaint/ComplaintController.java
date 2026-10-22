package com.ridex.ridex_app.complaint;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Complaints. Who may call what is decided in SecurityConfig: riders and drivers for /api/complaints, administrators for /api/admin/complaints. */
@RestController
public class ComplaintController {

    public record FileRequest(@NotNull UUID rideId, @NotNull Complaint.Category category,
                              @NotBlank @Size(min = 10, max = 1000, message = "must be between 10 and 1000 characters") String description) {
    }

    public record MessageRequest(@NotBlank @Size(max = 1000) String message, Boolean internal) {
    }

    public record ResolveRequest(@NotBlank @Size(max = 1000) String note, @DecimalMin("0.01") BigDecimal refundAmount) {
    }

    public record RejectRequest(@NotBlank @Size(max = 1000) String note) {
    }

    private final ComplaintService service;

    public ComplaintController(ComplaintService service) {
        this.service = service;
    }

    private static ActorRole roleOf(AuthenticatedUser user) {
        return ActorRole.valueOf(user.role().name());
    }

    // ------------------------------------------------------------------ riders and drivers

    @PostMapping("/api/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    public ComplaintService.ComplaintView file(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody FileRequest request) {
        return service.file(user.id(), roleOf(user), request.rideId(), request.category(), request.description());
    }

    @GetMapping("/api/complaints")
    public PageResponse<ComplaintService.ComplaintView> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.mine(user.id(), page, size);
    }

    @GetMapping("/api/complaints/{id}")
    public ComplaintService.ComplaintView mine(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.mine(user.id(), id);
    }

    @PostMapping("/api/complaints/{id}/messages")
    public ComplaintService.ComplaintView reply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        return service.reply(user.id(), roleOf(user), id, request.message());
    }

    // ------------------------------------------------------------------ administrators

    @GetMapping("/api/admin/complaints")
    public PageResponse<ComplaintService.ComplaintView> list(@RequestParam(required = false) Complaint.Status status,
                                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/api/admin/complaints/{id}")
    public ComplaintService.ComplaintView detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/api/admin/complaints/{id}/assign")
    public ComplaintService.ComplaintView assign(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
        return service.assign(admin.id(), id);
    }

    @PostMapping("/api/admin/complaints/{id}/messages")
    public ComplaintService.ComplaintView comment(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        return service.comment(admin.id(), id, request.message(), Boolean.TRUE.equals(request.internal()));
    }

    @PostMapping("/api/admin/complaints/{id}/resolve")
    public ComplaintService.ComplaintView resolve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
        return service.resolve(admin.id(), id, request.note(), request.refundAmount());
    }

    @PostMapping("/api/admin/complaints/{id}/reject")
    public ComplaintService.ComplaintView reject(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id, @Valid @RequestBody RejectRequest request) {
        return service.reject(admin.id(), id, request.note());
    }
}
