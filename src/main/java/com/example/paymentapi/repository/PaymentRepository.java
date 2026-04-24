package com.example.paymentapi.repository;

import com.example.paymentapi.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String>, JpaSpecificationExecutor<Payment> {
    List<Payment> findBySourceAccount(String sourceAccount);
    List<Payment> findBySourceAccountAndCreatedBy(String sourceAccount, String createdBy);
    List<Payment> findByDestinationAccount(String destinationAccount);
    List<Payment> findByDestinationAccountAndCreatedBy(String destinationAccount, String createdBy);
    List<Payment> findByStatusAndRetryCountLessThan(String status, int maxRetryCount);
    Optional<Payment> findByTemporalWorkflowId(String temporalWorkflowId);

    /** Returns [status, count] pairs for all known statuses — used by admin stats. */
    @Query("SELECT p.status, COUNT(p) FROM Payment p GROUP BY p.status")
    List<Object[]> countGroupedByStatus();

    /** Returns a single-row list of [count, sum(amount)] for payments created on or after startOfDay. */
    @Query("SELECT COUNT(p), COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.createdAt >= :startOfDay")
    List<Object[]> getTodayStats(@Param("startOfDay") LocalDateTime startOfDay);

    /** Bulk-deletes terminal payments whose last update is older than the cutoff. */
    @Modifying
    @Query("DELETE FROM Payment p WHERE p.status IN :statuses AND p.updatedAt < :cutoff")
    int deleteTerminalPaymentsOlderThan(@Param("statuses") Collection<String> statuses,
                                        @Param("cutoff") LocalDateTime cutoff);
}
