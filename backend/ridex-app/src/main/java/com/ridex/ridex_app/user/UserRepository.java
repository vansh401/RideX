package com.ridex.ridex_app.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByRole(Role role);

    java.util.List<User> findByRole(Role role);

    long countByStatus(UserStatus status);

    @Query("select u.role, count(u) from User u group by u.role")
    java.util.List<Object[]> countGroupedByRole();
}