package com.ridex.ridex_app.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Closes the gap that stateless JWTs leave open. An access token is checked by signature and
 * expiry only, so without this a suspended user (or a logged-out one) could keep calling the API
 * until the token's 15 minutes run out.
 *
 * Two kinds of revocation:
 * - the WHOLE user (suspension, password reset, stolen-refresh-token detection): every token
 *   issued up to that moment stops working;
 * - ONE token (logout): just that token id (jti).
 *
 * Entries only need to live as long as the tokens they cancel, so Redis expires them itself.
 */
public interface AccessTokenRevocations {

    /** Every access token issued to this user up to now becomes invalid. Later logins work. */
    void revokeAllFor(UUID userId);

    /** One token becomes invalid until it would have expired anyway. */
    void revoke(String jti, Instant tokenExpiresAt);

    /** @param issuedAtMillis when the token was created, with millisecond precision */
    boolean isRevoked(UUID userId, String jti, long issuedAtMillis);
}
