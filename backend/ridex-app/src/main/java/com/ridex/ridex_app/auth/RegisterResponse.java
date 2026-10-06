package com.ridex.ridex_app.auth;

import java.util.UUID;

import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserStatus;

public record RegisterResponse(
        UUID id,
        String fullName,
        String email,
        Role role,
        UserStatus status
) {
    public static RegisterResponse from(User user) {
        return new RegisterResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus());
    }
}