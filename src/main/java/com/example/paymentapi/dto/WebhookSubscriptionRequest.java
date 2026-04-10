package com.example.paymentapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookSubscriptionRequest {

    @NotBlank(message = "targetUrl is required")
    @URL(message = "targetUrl must be a valid URL")
    private String targetUrl;

    @NotBlank(message = "bearerToken is required")
    @Size(max = 512, message = "bearerToken must not exceed 512 characters")
    private String bearerToken;

    @NotEmpty(message = "eventTypes must not be empty")
    @Size(max = 10, message = "eventTypes must not contain more than 10 entries")
    private List<String> eventTypes;

    private boolean adminScope = false;

    @Builder.Default
    private boolean active = true;
}
