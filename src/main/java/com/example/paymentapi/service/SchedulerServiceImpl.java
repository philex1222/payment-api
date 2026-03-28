package com.example.paymentapi.service;

import com.example.paymentapi.model.Payment;
import com.example.paymentapi.model.PaymentStatus;
import com.example.paymentapi.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchedulerServiceImpl implements SchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerServiceImpl.class);

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    @Value("${scheduler.retry.max-attempts:3}")
    private int maxRetryAttempts;

    public SchedulerServiceImpl(PaymentRepository paymentRepository, PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
    }

    @Override
    @Scheduled(fixedDelayString = "${scheduler.retry.fixed-delay-ms:60000}")
    public void retryFailedPayments() {
        List<Payment> eligible = paymentRepository
                .findByStatusAndRetryCountLessThan(PaymentStatus.FAILED.getCode(), maxRetryAttempts);

        if (eligible.isEmpty()) {
            logger.debug("Retry job: no eligible FAILED payments");
            return;
        }

        logger.info("Retry job: retrying {} FAILED payment(s) (max attempts: {})", eligible.size(), maxRetryAttempts);
        for (Payment payment : eligible) {
            try {
                paymentService.retryPayment(payment.getId());
            } catch (Exception e) {
                logger.error("Retry job: could not retry payment {}: {}", payment.getId(), e.getMessage());
            }
        }
    }

    @Override
    @Scheduled(cron = "${scheduler.cleanup.cron:0 0 2 * * ?}")
    public void cleanupOldRecords() {
        logger.info("Cleanup job: running nightly maintenance");
        // Placeholder for future tasks: archive old audit logs, purge cancelled records, etc.
    }
}