package com.example.paymentapi.controller;

import com.example.paymentapi.dto.WebhookDeliveryResponse;
import com.example.paymentapi.dto.WebhookSubscriptionRequest;
import com.example.paymentapi.dto.WebhookSubscriptionResponse;
import com.example.paymentapi.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/webhooks")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
@Tag(name = "Webhooks", description = "Webhook subscription management — configure push notifications for payment events")
@SecurityRequirement(name = "bearerAuth")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    @Operation(summary = "Register a new webhook subscription",
               description = "Subscribe to one or more payment event types. Set adminScope=true (ADMIN only) to receive events for all users.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "400", description = "Invalid request or event type"),
        @ApiResponse(responseCode = "403", description = "adminScope=true requires ROLE_ADMIN")
    })
    public ResponseEntity<WebhookSubscriptionResponse> createSubscription(
            @Valid @RequestBody WebhookSubscriptionRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(webhookService.createSubscription(request, principal.getUsername()));
    }

    @GetMapping
    @Operation(summary = "List webhook subscriptions",
               description = "Users see only their own subscriptions. Admins see all.")
    @ApiResponse(responseCode = "200", description = "Subscriptions returned")
    public ResponseEntity<List<WebhookSubscriptionResponse>> listSubscriptions(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(webhookService.listSubscriptions(principal.getUsername(), isAdmin(principal)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a webhook subscription by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscription found"),
        @ApiResponse(responseCode = "403", description = "Subscription belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    public ResponseEntity<WebhookSubscriptionResponse> getSubscription(
            @Parameter(description = "Subscription ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(webhookService.getSubscription(id, principal.getUsername(), isAdmin(principal)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a webhook subscription",
               description = "All fields in the request body replace existing values. Set active=false to soft-disable.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Subscription updated"),
        @ApiResponse(responseCode = "400", description = "Invalid event type"),
        @ApiResponse(responseCode = "403", description = "Ownership or adminScope violation"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    public ResponseEntity<WebhookSubscriptionResponse> updateSubscription(
            @Parameter(description = "Subscription ID") @PathVariable String id,
            @Valid @RequestBody WebhookSubscriptionRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(webhookService.updateSubscription(id, request, principal.getUsername(), isAdmin(principal)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a webhook subscription")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Deleted"),
        @ApiResponse(responseCode = "403", description = "Subscription belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Not found")
    })
    public ResponseEntity<Void> deleteSubscription(
            @Parameter(description = "Subscription ID") @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {
        webhookService.deleteSubscription(id, principal.getUsername(), isAdmin(principal));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deliveries")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get delivery history for a subscription (admin only)",
               description = "Returns all delivery attempts for the given subscription ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery list returned"),
        @ApiResponse(responseCode = "403", description = "Requires ROLE_ADMIN")
    })
    public ResponseEntity<List<WebhookDeliveryResponse>> getDeliveries(
            @Parameter(description = "Subscription ID") @PathVariable String id) {
        return ResponseEntity.ok(webhookService.getDeliveries(id));
    }

    private boolean isAdmin(UserDetails principal) {
        return principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
