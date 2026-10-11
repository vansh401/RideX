package com.ridex.ridex_app.auth;

import org.springframework.security.core.AuthenticationException;

/** The refresh token is unknown, expired, revoked or its owner may no longer log in. Mapped to HTTP 401. */
public class InvalidRefreshTokenException extends AuthenticationException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
