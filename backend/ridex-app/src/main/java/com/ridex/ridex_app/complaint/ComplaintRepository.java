package com.ridex.ridex_app.complaint;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    boolean existsByRideIdAndFiledByAndStatusIn(UUID rideId, UUID filedBy, Collection<Complaint.Status> statuses);

    Page<Complaint> findByFiledBy(UUID filedBy, Pageable pageable);

    Page<Complaint> findByStatus(Complaint.Status status, Pageable pageable);

    List<Complaint> findByRideIdOrderByCreatedAtDesc(UUID rideId);

    long countByStatus(Complaint.Status status);
}
