package com.ridex.ridex_app.admin;

import java.util.UUID;

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
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.UserStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: users", description = "Find, suspend and reinstate accounts")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search users", description = "Filter by role and status; q matches name or email. Newest first.")
    public PageResponse<AdminUserResponse> list(@RequestParam(required = false) Role role,
                                                @RequestParam(required = false) UserStatus status,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.list(role, status, q, page, size);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend an account",
            description = "Not yourself, not an admin, and only an ACTIVE account. Revokes its refresh tokens.")
    public AdminUserResponse suspend(@AuthenticationPrincipal AuthenticatedUser principal,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody SuspendUserRequest request) {
        return service.suspend(principal.id(), id, request.reason());
    }

    @PostMapping("/{id}/reinstate")
    @Operation(summary = "Lift a suspension", description = "Only for suspended accounts.")
    public AdminUserResponse reinstate(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return service.reinstate(principal.id(), id);
    }
}
