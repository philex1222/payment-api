Feature: Payment Lifecycle
  In order to process financial transactions
  As an authenticated user
  I want to create, track, and manage payments through their full lifecycle

  Background:
    Given I am authenticated as "admin"

  Scenario: Create a payment — verify structure and account masking
    When I create a payment for 100 USD from "1234567890" to "0987654321"
    Then the response status code is 201
    And the payment status is "COMPLETED"
    And the payment has an "id" field
    And account numbers are masked in the response

  Scenario: Reverse a completed payment
    When I create a payment for 50 USD from "1234567890" to "0987654321"
    Then the payment is created successfully
    When I initiate a reversal of the payment with reason "Customer requested refund for BDD test"
    Then the response status code is 200
    And the payment status is "REVERSED"

  Scenario: Cancel a completed payment returns conflict
    When I create a payment for 25 USD from "1234567890" to "0987654321"
    Then the payment is created successfully
    When I cancel the payment
    Then the response status code is 409

  Scenario: Idempotency key prevents duplicate payments
    Given an idempotency key "bdd-idem-key-lifecycle-001"
    When I create a payment with that idempotency key
    And I create another payment with the same idempotency key
    Then only one payment exists with that idempotency key

  Scenario: Zero-amount payment is rejected
    When I create a payment for 0 USD from "1234567890" to "0987654321"
    Then the response status code is 400
