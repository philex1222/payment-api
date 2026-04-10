Feature: Webhook Delivery
  As an authenticated user with a registered webhook
  I want my webhook to receive deliveries when payments change status
  So that my system can react to payment events in real time

  Background:
    Given I am logged in as "user" with password "password"

  Scenario: Delivery is queued after a payment is created
    Given I register a webhook for events "PAYMENT_CREATED" pointing to "ECHO" with token "tok"
    And I save the subscription ID
    When I create a payment for 50 USD from "1234567890" to "0987654321"
    And I wait 500ms for async processing
    Then the webhook subscription has at least 1 pending or delivered delivery

  Scenario: Inactive subscription does not receive deliveries
    Given I register a webhook for events "PAYMENT_CREATED" pointing to "ECHO" with token "tok" and active false
    And I save the subscription ID
    When I create a payment for 50 USD from "1234567890" to "0987654321"
    And I wait 500ms for async processing
    Then the webhook subscription has 0 deliveries
