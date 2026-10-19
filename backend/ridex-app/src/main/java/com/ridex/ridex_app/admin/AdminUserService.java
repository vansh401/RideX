package com.ridex.ridex_app.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ridex.ridex_app.auth.RefreshTokenService;
import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.common.Paging;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.driver.DriverAvailabilityService;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserRepository;
import com.ridex.ridex_app.user.UserStatus;

import jakarta.persistence.criteria.Predicate;

@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final DriverAvailabilityService availabilityService;

    public AdminUserService(UserRepository userRepository,
                            RefreshTokenService refreshTokenService,
                            DriverAvailabilityService availabilityService) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.availabilityService = availabilityService;
    }

    /** Search by name or email, filter by role and status. Newest accounts first. */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(Role role, UserStatus status, String query, int page, int size) {
        Pageable pageable = Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> result = userRepository.findAll(filter(role, status, query), pageable);
        return PageResponse.from(result, AdminUserResponse::from);
    }

    /**
     * Suspends an account: it can no longer log in or use any write endpoint (every one re-checks
     * the account in the database), its refresh tokens are revoked, and a driver stops receiving
     * offers. Access tokens already issued are cancelled in Redis (see RefreshTokenService.revokeAllForUser),
     * so the user is locked out immediately, not after the token expires.
     */
    @Transactional
    public AdminUserResponse suspend(UUID adminId, UUID userId, String reason) {
        User user = load(userId);
        if (user.getId().equals(adminId)) {
            throw new ConflictException("You cannot suspend your own account");
        }
        if (user.getRole() == Role.ADMIN) {
            throw new ConflictException("Admin accounts cannot be suspended here");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ConflictException("Only an active account can be suspended");
        }
        user.suspend();
        refreshTokenService.revokeAllForUser(userId);
        if (user.getRole() == Role.DRIVER) {
            availabilityService.forceOffline(userId);
        }
        log.info("Admin {} suspended user {}: {}", adminId, userId, reason);
        return AdminUserResponse.from(user);
    }

    @Transactional
    public AdminUserResponse reinstate(UUID adminId, UUID userId) {
        User user = load(userId);
        user.reinstate();
        log.info("Admin {} reinstated user {}", adminId, userId);
        return AdminUserResponse.from(user);
    }

    private User load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private static Specification<User> filter(Role role, UserStatus status, String query) {
        return (root, criteria, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                predicates.add(builder.equal(root.get("role"), role));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (query != null && !query.isBlank()) {
                // Escape LIKE wildcards typed by the admin so "50%" searches for "50%"
                String text = query.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                String pattern = "%" + text + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("fullName")), pattern, '\\'),
                        builder.like(builder.lower(root.get("email")), pattern, '\\')));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
