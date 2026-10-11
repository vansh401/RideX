package com.ridex.ridex_app.security;

import java.util.UUID;

import com.ridex.ridex_app.user.Role;

/** Who is calling, as established from a verified JWT. */
public record AuthenticatedUser(UUID id, Role role) {
}
