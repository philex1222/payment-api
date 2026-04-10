package com.example.paymentapi.service;

import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls for pending webhook deliveries and dispatches them.
 *
 * <p>The actual per-delivery work (HTTP POST, retry logic, DB update) is delegated
 * to {@link WebhookDeliveryExecutor} — a separate Spring bean — so that the
 * {@code @Transactional} annotation on {@code dispatchSingle} is applied by the
 * Spring proxy rather than bypassed by a same-bean self-invocation.</p>
 */
@Service
public class WebhookDispatcherService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDispatcherService.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryExecutor deliveryExecutor;

    public WebhookDispatcherService(WebhookDeliveryRepository deliveryRepository,
                                     WebhookDeliveryExecutor deliveryExecutor) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryExecutor = deliveryExecutor;
    }

    @Scheduled(fixedDelayString = "${webhook.dispatcher.fixed-delay-ms:30000}")
    public void dispatchPendingDeliveries() {
        List<WebhookDelivery> pending = deliveryRepository.findPendingDeliveries(
                LocalDateTime.now(), WebhookDeliveryExecutor.MAX_ATTEMPTS);
        if (pending.isEmpty()) return;
        logger.debug("Dispatching {} pending webhook deliveries", pending.size());
        for (WebhookDelivery delivery : pending) {
            deliveryExecutor.dispatchSingle(delivery);
        }
    }
}
