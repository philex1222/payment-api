package com.example.paymentapi.service.query;

import com.example.paymentapi.dto.PaymentResponse;
import com.example.paymentapi.exception.PaymentNotFoundException;
import com.example.paymentapi.model.Payment;
import com.example.paymentapi.repository.PaymentRepository;
import com.example.paymentapi.repository.PaymentSpecification;
import com.example.paymentapi.service.shared.PaymentMapper;
import com.example.paymentapi.service.shared.PaymentSecurityHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentQueryService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentMapper mapper;
    private final PaymentSecurityHelper security;

    public PaymentQueryService(PaymentRepository paymentRepository,
                               PaymentMapper mapper,
                               PaymentSecurityHelper security) {
        this.paymentRepository = paymentRepository;
        this.mapper = mapper;
        this.security = security;
    }

    public PaymentResponse findById(String id) {
        logger.debug("Retrieving payment: {}", id);
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found with ID: " + id));
        security.checkOwnership(payment);
        return mapper.toResponse(payment);
    }

    public Page<PaymentResponse> findAll(String status, LocalDateTime dateFrom, LocalDateTime dateTo,
                                         BigDecimal amountFrom, BigDecimal amountTo,
                                         String currency, Pageable pageable) {
        Specification<Payment> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank())
            spec = spec.and(PaymentSpecification.hasStatus(status));
        if (dateFrom != null)
            spec = spec.and(PaymentSpecification.createdAfter(dateFrom));
        if (dateTo != null)
            spec = spec.and(PaymentSpecification.createdBefore(dateTo));
        if (amountFrom != null)
            spec = spec.and(PaymentSpecification.amountGreaterThanOrEqual(amountFrom));
        if (amountTo != null)
            spec = spec.and(PaymentSpecification.amountLessThanOrEqual(amountTo));
        if (currency != null && !currency.isBlank())
            spec = spec.and(PaymentSpecification.hasCurrency(currency));
        if (!security.isCurrentUserAdmin())
            spec = spec.and(PaymentSpecification.ownedBy(security.currentUsername()));
        return paymentRepository.findAll(spec, pageable).map(mapper::toResponse);
    }

    public List<PaymentResponse> findBySourceAccount(String sourceAccount) {
        List<Payment> payments = security.isCurrentUserAdmin()
                ? paymentRepository.findBySourceAccount(sourceAccount)
                : paymentRepository.findBySourceAccountAndCreatedBy(sourceAccount, security.currentUsername());
        return payments.stream().map(mapper::toResponse).toList();
    }

    public List<PaymentResponse> findByDestinationAccount(String destinationAccount) {
        List<Payment> payments = security.isCurrentUserAdmin()
                ? paymentRepository.findByDestinationAccount(destinationAccount)
                : paymentRepository.findByDestinationAccountAndCreatedBy(destinationAccount, security.currentUsername());
        return payments.stream().map(mapper::toResponse).toList();
    }
}
