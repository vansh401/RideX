package com.ridex.ridex_app.complaint;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintCommentRepository extends JpaRepository<ComplaintComment, UUID> {

    List<ComplaintComment> findByComplaintIdOrderByCreatedAt(UUID complaintId);

    List<ComplaintComment> findByComplaintIdAndInternalFalseOrderByCreatedAt(UUID complaintId);
}
