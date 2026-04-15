package com.example.paymentapi.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only HTTP endpoint that acts as a webhook target URL in BDD delivery scenarios.
 * Active only with the "test" profile — never deployed to production.
 */
@RestController
@Profile("test")
@RequestMapping("/test")
public class WebhookTestController {

    @PostMapping("/webhook-echo")
    public ResponseEntity<String> echoWebhook(@RequestBody String body) {
        return ResponseEntity.ok("received");
    }
}
