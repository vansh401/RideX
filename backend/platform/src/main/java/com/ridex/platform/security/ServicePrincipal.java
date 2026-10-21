package com.ridex.platform.security;

import java.util.UUID;

/** Who is calling a service, as established from a verified JWT. */
public record ServicePrincipal(UUID id, String role) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
