package com.example.paymentapi.service;

import com.example.paymentapi.model.WebhookDeliveryStatus;
import com.example.paymentapi.repository.WebhookDeliveryRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryExecutorTest {

    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private WebhookSubscriptionRepository subscriptionRepository;
    @Mock private RestClient webhookRestClient;

    private WebhookDeliveryExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new WebhookDeliveryExecutor(
                deliveryRepository, subscriptionRepository, webhookRestClient);
    }

    // ── Mock helpers (same chain-mock pattern as WebhookDispatcherServiceTest) ──

    private void mockChainReturning(int httpStatus) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(webhookRestClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.contentType(any(MediaType.class))).thenReturn(uriSpec);
        lenient().when(uriSpec.body(any(byte[].class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity())
                .thenReturn(ResponseEntity.status(httpStatus).build());
    }

    private void mockChainThrowingFromRetrieve(RuntimeException ex) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        when(webhookRestClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.contentType(any(MediaType.class))).thenReturn(uriSpec);
        lenient().when(uriSpec.body(any(byte[].class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenThrow(ex);
    }

    private void mockChainThrowingFromToBodilessEntity(RuntimeException ex) {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(webhookRestClient.post()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        lenient().when(uriSpec.contentType(any(MediaType.class))).thenReturn(uriSpec);
        lenient().when(uriSpec.body(any(byte[].class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenThrow(ex);
    }

    // ── executePost tests ──────────────────────────────────────────────────────

    @Test
    void executePost_200_returns200() {
        mockChainReturning(200);
        assertThat(executor.executePost("http://example.com/hook", "token", "{}"))
                .isEqualTo(200);
    }

    @Test
    void executePost_201_returns201() {
        mockChainReturning(201);
        assertThat(executor.executePost("http://example.com/hook", "token", "{}"))
                .isEqualTo(201);
    }

    @Test
    void executePost_httpClientError400_returns400() {
        mockChainThrowingFromToBodilessEntity(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        assertThat(executor.executePost("http://example.com/hook", "token", "{}"))
                .isEqualTo(400);
    }

    @Test
    void executePost_httpServerError500_returns500() {
        mockChainThrowingFromToBodilessEntity(
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(executor.executePost("http://example.com/hook", "token", "{}"))
                .isEqualTo(500);
    }

    @Test
    void executePost_networkFailure_returns0() {
        // URL contains sensitive query param — executor strips query string before logging
        mockChainThrowingFromRetrieve(new RuntimeException("Connection refused"));
        assertThat(executor.executePost(
                "http://example.com/hook?secret=sensitive", "token", "{}"))
                .isEqualTo(0);
    }
}
