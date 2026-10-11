package com.ridex.ridex_app.auth;

import java.util.Locale;
import java.util.Optional;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ForbiddenException;
import com.ridex.ridex_app.jwt.JwtService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;

    // Hash of a throwaway password. Checked when the email is unknown so that
    // "unknown email" and "wrong password" take about the same time.
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.emailVerificationService = emailVerificationService;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public User register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phone().trim();

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new ConflictException("Phone number is already registered");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        Role role = Role.valueOf(request.role().name());

        User user = new User(request.fullName(), email, phone, passwordHash, role);
        User saved = userRepository.save(user);
        emailVerificationService.issueFor(saved);   // the email goes out after this commits
        return saved;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> found = userRepository.findByEmail(email);

        // Always run one BCrypt comparison, whether or not the user exists
        String hashToCheck = found.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (found.isEmpty() || !passwordMatches) {
            throw new BadCredentialsException("Invalid email or password");
        }

        User user = found.get();
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ForbiddenException("This account has been suspended");
        }
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new ForbiddenException("Please verify your email before logging in");
        }

        return issueTokens(user, refreshTokenService.issueForNewSession(user.getId()));
    }

    /**
     * Trades a refresh token for a new access token and a new refresh token (rotation).
     * Unlike the access token, this re-reads the user, so a changed role or a suspension
     * takes effect here. noRollbackFor keeps the revocations done before an exception.
     */
    @Transactional(noRollbackFor = AuthenticationException.class)
    public LoginResponse refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken);

        User user = userRepository.findById(rotation.userId()).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            refreshTokenService.revokeAllForUser(rotation.userId());
            throw new InvalidRefreshTokenException("Invalid refresh token");
        }
        return issueTokens(user, rotation.newToken());
    }

    /** Logout: the whole session (token family) stops being refreshable. Always succeeds. */
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeSessionOf(rawRefreshToken);
    }

    private LoginResponse issueTokens(User user, RefreshTokenService.IssuedToken refreshToken) {
        JwtService.AccessToken access = jwtService.createAccessToken(user);
        return new LoginResponse(access.value(), refreshToken.value(), "Bearer",
                access.expiresInSeconds(), UserSummary.from(user));
    }
}