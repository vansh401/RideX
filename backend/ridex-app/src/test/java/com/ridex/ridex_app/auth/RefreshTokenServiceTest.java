package com.ridex.ridex_app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefreshTokenServiceTest {

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final com.ridex.ridex_app.security.AccessTokenRevocations accessTokens =
            mock(com.ridex.ridex_app.security.AccessTokenRevocations.class);
    private final RefreshTokenService service = new RefreshTokenService(repository, accessTokens, 7);

    private final UUID userId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();

    private RefreshToken stored(String raw, Instant expiresAt) {
        RefreshToken token = new RefreshToken(userId, familyId, RefreshTokenService.hash(raw), expiresAt);
        when(repository.findByTokenHashForUpdate(RefreshTokenService.hash(raw)))
                .thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void issuedTokenIsStoredOnlyAsAHash() {
        RefreshTokenService.IssuedToken issued = service.issueForNewSession(userId);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(issued.value()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(RefreshTokenService.hash(issued.value()))
                .isNotEqualTo(issued.value());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(issued.expiresAt()).isAfter(Instant.now().plusSeconds(6 * 24 * 3600));
    }

    @Test
    void rotationRevokesTheOldTokenAndIssuesANewOneInTheSameFamily() {
        RefreshToken old = stored("old", Instant.now().plusSeconds(3600));

        RefreshTokenService.Rotation rotation = service.rotate("old");

        assertThat(old.isRevoked()).isTrue();
        assertThat(rotation.userId()).isEqualTo(userId);
        assertThat(rotation.newToken().value()).isNotEqualTo("old");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getFamilyId()).isEqualTo(familyId);
    }

    @Test
    void unknownTokenIsRejected() {
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("nope"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        stored("old", Instant.now().minusSeconds(5));

        assertThatThrownBy(() -> service.rotate("old"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void reusingAnAlreadyRotatedTokenRevokesTheWholeFamily() {
        RefreshToken old = stored("old", Instant.now().plusSeconds(3600));
        old.revoke(Instant.now());   // it was already used once

        assertThatThrownBy(() -> service.rotate("old"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(repository).revokeFamily(eq(familyId), any(Instant.class));
        verify(repository, never()).save(any());
    }

    @Test
    void logoutRevokesTheFamilyOfTheToken() {
        stored("current", Instant.now().plusSeconds(3600));

        service.revokeSessionOf("current");

        verify(repository).revokeFamily(eq(familyId), any(Instant.class));
    }

    @Test
    void logoutWithAnUnknownTokenDoesNothing() {
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        service.revokeSessionOf("nope");

        verify(repository, never()).revokeFamily(any(), any());
    }
}
