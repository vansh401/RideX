package com.ridex.ridex_app.auth;

import java.util.UUID;

import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

public record UserSummary(UUID id, String fullName, String email, Role role) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getFullName(), user.getEmail(), user.getRole());
    }
}