package com.ridex.ridex_app.admin;

import java.time.Instant;
import java.util.UUID;

import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserStatus;

public record AdminUserResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        Instant createdAt
) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
