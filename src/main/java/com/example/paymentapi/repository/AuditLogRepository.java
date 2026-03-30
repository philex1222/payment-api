package com.example.paymentapi.repository;

import com.example.paymentapi.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Bulk-deletes all audit log entries whose timestamp is older than the given cutoff. */
    @Modifying
    @Query("DELETE FROM AuditLog a WHERE a.timestamp < :cutoff")
    int deleteAuditLogsOlderThan(@Param("cutoff") LocalDateTime cutoff);
}