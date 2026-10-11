package com.ridex.ridex_app.user;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first admin at startup from configuration (environment variables), because the
 * public register endpoint can never create one.
 *
 * Safe by design: it does nothing unless both email and password are configured, does nothing
 * if an admin already exists, never promotes an existing account, and never logs the password.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_LENGTH = 72;   // BCrypt ignores anything beyond 72 bytes

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String fullName;
    private final String phone;

    public AdminSeeder(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${ridex.admin.email:}") String email,
                       @Value("${ridex.admin.password:}") String password,
                       @Value("${ridex.admin.full-name:RideX Admin}") String fullName,
                       @Value("${ridex.admin.phone:+910000000000}") String phone) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.phone = phone;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.debug("No admin credentials configured; skipping admin seeding");
            return;
        }
        if (userRepository.existsByRole(Role.ADMIN)) {
            log.info("An admin account already exists; skipping admin seeding");
            return;
        }

        // Configuration mistakes stop the application instead of silently creating a weak admin
        // Same policy as registration: an admin password is never weaker than anyone else's
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH
                || !password.matches("^(?=.*[A-Za-z])(?=.*\\d).+$")) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD must be " + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH
                            + " characters with at least one letter and one digit");
        }
        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(normalisedEmail)) {
            throw new IllegalStateException(
                    "ADMIN_EMAIL belongs to an existing non-admin account; refusing to promote it. "
                            + "Choose a different email.");
        }
        if (userRepository.existsByPhone(phone.trim())) {
            throw new IllegalStateException(
                    "ADMIN_PHONE is already used by another account. Set a different ADMIN_PHONE.");
        }

        User admin = new User(fullName, normalisedEmail, phone, passwordEncoder.encode(password), Role.ADMIN);
        admin.activate();
        userRepository.save(admin);

        log.info("Created initial admin account {}", normalisedEmail);
    }
}
