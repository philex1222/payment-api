package com.example.paymentapi.repository;

import com.example.paymentapi.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {
    List<Payment> findBySourceAccount(String sourceAccount);
    List<Payment> findByDestinationAccount(String destinationAccount);
}