package com.ridex.ridex_app.auth;

import java.time.Instant;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.security.AccessTokenRevocations;
import com.ridex.ridex_app.security.JwtAuthenticationFilter;
import com.ridex.ridex_app.security.LoginAttemptLimiter;
import com.ridex.ridex_app.user.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration, login, tokens, email verification and password reset. All public.")
@SecurityRequirements   // no Authorization header needed for anything here
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final AccessTokenRevocations accessTokenRevocations;

    public AuthController(AuthService authService,
                          EmailVerificationService emailVerificationService,
                          PasswordResetService passwordResetService,
                          LoginAttemptLimiter loginAttemptLimiter,
                          AccessTokenRevocations accessTokenRevocations) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.accessTokenRevocations = accessTokenRevocations;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a rider or driver account",
            description = "The account starts as PENDING_VERIFICATION; a verification email is sent. "
                    + "ADMIN accounts can never be created here.")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return RegisterResponse.from(user);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password",
            description = "Returns a short-lived access token and a refresh token. After repeated wrong "
                    + "passwords for the same email from the same address the answer is 429 for a while.")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String client = http.getRemoteAddr();
        loginAttemptLimiter.assertNotLocked(email, client);   // before any password hashing
        try {
            LoginResponse response = authService.login(request);
            loginAttemptLimiter.clear(email, client);
            return response;
        } catch (BadCredentialsException e) {
            loginAttemptLimiter.recordFailure(email, client);
            throw e;
        }
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for new tokens",
            description = "The old refresh token stops working (rotation). Reusing an old one "
                    + "revokes the whole session.")
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End the session behind a refresh token",
            description = "Always 204, even for unknown tokens. If the call carries the access token, that token "
                    + "is cancelled too, so it stops working immediately instead of after its 15 minutes.")
    public void logout(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest http) {
        authService.logout(request.refreshToken());
        Object jti = http.getAttribute(JwtAuthenticationFilter.ATTR_JTI);
        Object expiresAt = http.getAttribute(JwtAuthenticationFilter.ATTR_EXPIRES_AT);
        if (jti instanceof String id && expiresAt instanceof Instant expiry) {
            accessTokenRevocations.revoke(id, expiry);
        }
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirm an email address with the emailed token")
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.token());
    }

    @PostMapping("/verify-otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirm an email address with the 6-digit code from the email",
            description = "The code expires after a few minutes and allows only a few wrong guesses.")
    public void verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        emailVerificationService.verifyWithCode(request.email(), request.code());
    }

    // Always 202, whether or not the email exists, so it cannot be used to find registered emails
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Send a new verification email",
            description = "Always 202, whether or not the email is registered.")
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resend(request.email());
    }

    // Always 202, whether or not the email exists, so it cannot be used to find registered emails
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Email a password reset link",
            description = "Always 202, whether or not the email is registered.")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set a new password with the emailed token",
            description = "Also ends every existing session of that user.")
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
    }
}
