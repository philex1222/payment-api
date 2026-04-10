Feature: Webhook Subscription Management
  As an authenticated user
  I want to manage webhook subscriptions
  So that I receive push notifications when my payment status changes

  Background:
    Given I am logged in as "user" with password "password"

  Scenario: Register and retrieve a webhook subscription
    When I register a webhook for events "PAYMENT_COMPLETED,PAYMENT_FAILED" pointing to "http://example.com/hook" with token "secret"
    Then the response status is 201
    And the response contains a webhook subscription with targetUrl "http://example.com/hook"
    And the response bearerToken is masked as "***"

  Scenario: List my webhook subscriptions
    When I register a webhook for events "PAYMENT_CREATED" pointing to "http://example.com/hook1" with token "tok1"
    And I register a webhook for events "PAYMENT_FAILED" pointing to "http://example.com/hook2" with token "tok2"
    When I list my webhook subscriptions
    Then the response status is 200
    And the response contains at least 2 subscriptions

  Scenario: Update a webhook subscription
    When I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/old" with token "old-token"
    And I save the subscription ID
    When I update the subscription with targetUrl "http://example.com/new" events "PAYMENT_FAILED" and active "true"
    Then the response status is 200
    And the response contains a webhook subscription with targetUrl "http://example.com/new"

  Scenario: Delete a webhook subscription
    When I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/hook" with token "tok"
    And I save the subscription ID
    When I delete the webhook subscription
    Then the response status is 204

  Scenario: Cannot register adminScope subscription as regular user
    When I try to register an adminScope webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/hook"
    Then the response status is 403

  Scenario: Cannot access another user's subscription
    Given I am logged in as "admin" with password "password"
    When I register a webhook for events "PAYMENT_COMPLETED" pointing to "http://example.com/admin-hook" with token "admin-tok"
    And I save the subscription ID
    Given I am logged in as "user" with password "password"
    When I try to get the saved subscription
    Then the response status is 403
