package com.ridex.ridex_app.driver;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Driver review queue. Protected twice: by the /api/admin/** URL rule and by @PreAuthorize,
 * so a forgotten rule in one place does not open the door.
 */
@RestController
@RequestMapping("/api/admin/drivers")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: drivers", description = "Review and approve driver applications")
public class AdminDriverController {

    private final DriverReviewService service;

    public AdminDriverController(DriverReviewService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List driver applications", description = "Oldest first. Optional status filter.")
    public PageResponse<AdminDriverSummary> list(
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One application with its documents")
    public AdminDriverDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/documents/{documentId}/file")
    @Operation(summary = "Download a driver's document")
    public ResponseEntity<byte[]> document(@PathVariable UUID id, @PathVariable UUID documentId) {
        return DriverDocumentController.asDownload(service.document(id, documentId));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a driver",
            description = "Requires an uploaded driving licence that has not expired. 409 if not pending.")
    public AdminDriverDetail approve(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return service.approve(principal.id(), id);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a driver", description = "A reason is required and shown to the driver.")
    public AdminDriverDetail reject(@AuthenticationPrincipal AuthenticatedUser principal,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody RejectDriverRequest request) {
        return service.reject(principal.id(), id, request.reason());
    }
}
