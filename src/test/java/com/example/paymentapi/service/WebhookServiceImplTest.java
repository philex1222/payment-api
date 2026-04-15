package com.example.paymentapi.service;

import com.example.paymentapi.config.TestConfig;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;
import com.example.paymentapi.model.User;
import com.example.paymentapi.repository.UserRepository;
import com.example.paymentapi.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.example.paymentapi.exception.WebhookSubscriptionNotFoundException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
// Override the test-profile SSRF bypass so SSRF security tests execute as they would in production.
@TestPropertySource(properties = "webhook.ssrf.allow-localhost=false")
class WebhookServiceImplTest {

    @Autowired private WebhookService webhookService;
    @Autowired private UserRepository userRepository;
    @Autowired private WebhookSubscriptionRepository subscriptionRepository;

    private User regularUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        regularUser = userRepository.findByUsername("user").orElseThrow();
        adminUser   = userRepository.findByUsername("admin").orElseThrow();
    }

    @Test
    void createSubscription_savesAndReturnsMaskedToken() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("secret-token")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        WebhookSubscriptionResponse resp = webhookService.createSubscription(req, "user");

        assertThat(resp.getId()).isNotNull();
        assertThat(resp.getBearerToken()).isEqualTo("***");
        assertThat(resp.getEventTypes()).containsExactly("PAYMENT_COMPLETED");
        assertThat(resp.isActive()).isTrue();
        assertThat(resp.isAdminScope()).isFalse();
    }

    @Test
    void createSubscription_adminScope_rejectedForNonAdmin() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_CREATED"))
                .adminScope(true)
                .build();

        assertThatThrownBy(() -> webhookService.createSubscription(req, "user"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createSubscription_invalidEventType_throwsIllegalArgument() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_INVENTED"))
                .build();

        assertThatThrownBy(() -> webhookService.createSubscription(req, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAYMENT_INVENTED");
    }

    @Test
    void listSubscriptions_userSeesOnlyOwn() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");
        webhookService.createSubscription(req, "admin");

        List<WebhookSubscriptionResponse> userList = webhookService.listSubscriptions("user", false);
        assertThat(userList).allMatch(s -> s.getUserId().equals(regularUser.getId()));
    }

    @Test
    void listSubscriptions_adminSeesAll() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        webhookService.createSubscription(req, "user");
        webhookService.createSubscription(req, "admin");

        List<WebhookSubscriptionResponse> adminList = webhookService.listSubscriptions("admin", true);
        assertThat(adminList.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void getSubscription_crossUser_throwsAccessDenied() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        WebhookSubscriptionResponse created = webhookService.createSubscription(req, "admin");

        assertThatThrownBy(() -> webhookService.getSubscription(created.getId(), "user", false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteSubscription_removesFromDb() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        WebhookSubscriptionResponse created = webhookService.createSubscription(req, "user");

        webhookService.deleteSubscription(created.getId(), "user", false);

        assertThat(subscriptionRepository.findById(created.getId())).isEmpty();
    }

    @Test
    void getSubscription_notFound_throwsWebhookSubscriptionNotFound() {
        assertThatThrownBy(() -> webhookService.getSubscription("non-existent-id", "user", false))
                .isInstanceOf(WebhookSubscriptionNotFoundException.class);
    }

    @Test
    void createSubscription_localhostUrl_throwsIllegalArgument() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://localhost:8080/steal")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        assertThatThrownBy(() -> webhookService.createSubscription(req, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or internal network");
    }

    @Test
    void createSubscription_privateIpUrl_throwsIllegalArgument() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("http://192.168.1.1/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        assertThatThrownBy(() -> webhookService.createSubscription(req, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or internal network");
    }

    @Test
    void createSubscription_fileSchemeUrl_throwsIllegalArgument() {
        WebhookSubscriptionRequest req = WebhookSubscriptionRequest.builder()
                .targetUrl("file:///etc/passwd")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();

        assertThatThrownBy(() -> webhookService.createSubscription(req, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme must be http or https");
    }

    @Test
    void updateSubscription_deactivatesWithActiveFalse() {
        WebhookSubscriptionRequest createReq = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .build();
        WebhookSubscriptionResponse created = webhookService.createSubscription(createReq, "user");

        WebhookSubscriptionRequest updateReq = WebhookSubscriptionRequest.builder()
                .targetUrl("http://example.com/hook")
                .bearerToken("tok")
                .eventTypes(List.of("PAYMENT_COMPLETED"))
                .active(false)
                .build();
        WebhookSubscriptionResponse updated = webhookService.updateSubscription(created.getId(), updateReq, "user", false);

        assertThat(updated.isActive()).isFalse();
    }
}
