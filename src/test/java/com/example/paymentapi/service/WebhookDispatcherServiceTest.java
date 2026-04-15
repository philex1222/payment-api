package com.example.paymentapi.service;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.model.WebhookDelivery;
import com.example.paymentapi.model.WebhookDeliveryStatus;
import com.example.paymentapi.model.WebhookSubscription;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class WebhookDispatcherServiceTest {

    @Autowired private WebhookDispatcherService dispatcherService;
    @Autowired private WebhookDeliveryRepository deliveryRepository;
    @Autowired private WebhookSubscriptionRepository subscriptionRepository;

    @MockitoBean
    private RestClient webhookRestClient;

    private WebhookSubscription savedSub;

    @BeforeEach
    void setUp() {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setUserId(1L);
        sub.setTargetUrl("http://example.com/hook");
        sub.setBearerToken("secret-token");
        sub.setEventTypes("PAYMENT_COMPLETED");
        sub.setActive(true);
        savedSub = subscriptionRepository.save(sub);
    }

    private WebhookDelivery pendingDelivery() {
        WebhookDelivery d = new WebhookDelivery();
        d.setSubscriptionId(savedSub.getId());
        d.setPaymentId("pay-001");
        d.setEventType("PAYMENT_COMPLETED");
        d.setPayload("{\"eventType\":\"PAYMENT_COMPLETED\"}");
        d.setStatus(WebhookDeliveryStatus.PENDING);
        d.setAttemptCount(0);
        d.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return deliveryRepository.save(d);
    }

    private void mockRestClient2xx() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(webhookRestClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.contentType(any())).thenReturn(uriSpec);
        lenient().when(uriSpec.body(any(byte[].class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    private void mockRestClientFailure() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(webhookRestClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.contentType(any())).thenReturn(uriSpec);
        lenient().when(uriSpec.body(any(byte[].class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenThrow(new RuntimeException("Connection refused"));
    }

    @Test
    void dispatchPendingDeliveries_on2xx_marksDelivered() {
        mockRestClient2xx();
        WebhookDelivery delivery = pendingDelivery();

        dispatcherService.dispatchPendingDeliveries();

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getLastAttemptAt()).isNotNull();
    }

    @Test
    void dispatchPendingDeliveries_onFailure_incrementsAttemptAndSchedulesRetry() {
        mockRestClientFailure();
        WebhookDelivery delivery = pendingDelivery();

        dispatcherService.dispatchPendingDeliveries();

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getNextRetryAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void dispatchPendingDeliveries_afterMaxAttempts_marksAsFailed() {
        mockRestClientFailure();
        WebhookDelivery delivery = pendingDelivery();
        delivery.setAttemptCount(4); // one more attempt will reach 5 = WebhookDeliveryExecutor.MAX_ATTEMPTS
        deliveryRepository.save(delivery);

        dispatcherService.dispatchPendingDeliveries();

        WebhookDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(updated.getAttemptCount()).isEqualTo(5);
    }

    @Test
    void dispatchPendingDeliveries_futureRetryAt_skipped() {
        mockRestClient2xx();
        WebhookDelivery delivery = pendingDelivery();
        delivery.setNextRetryAt(LocalDateTime.now().plusMinutes(10));
        deliveryRepository.save(delivery);

        dispatcherService.dispatchPendingDeliveries();

        WebhookDelivery unchanged = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(unchanged.getAttemptCount()).isEqualTo(0);
    }
}
