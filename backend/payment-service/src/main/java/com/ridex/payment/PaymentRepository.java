package com.ridex.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByRideId(UUID rideId);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    /** Pending payments whose gateway call failed technically and whose waiting time is over. */
    @Query("select p.id from Payment p where p.status = com.ridex.payment.PaymentStatus.PENDING and p.nextAttemptAt is not null and p.nextAttemptAt <= :now order by p.nextAttemptAt")
    List<UUID> findDueIds(@Param("now") Instant now);
}
