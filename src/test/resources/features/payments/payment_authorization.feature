Feature: Payment Authorization
  In order to protect user data
  The API enforces strict ownership rules
  So that users can only view and modify their own payments

  Scenario: Regular user cannot access admin-only stats endpoint
    Given I am authenticated as "user"
    When I GET "/api/v1/admin/stats"
    Then the response status code is 403

  Scenario: User can only see their own payments — not payments of other users
    Given "admin" has created a payment
    When I am authenticated as "user"
    And I list all payments
    Then I do not see the admin's payment in the list

  Scenario: User cannot fetch another user's payment by ID
    Given "admin" has created a payment
    When I am authenticated as "user"
    And I GET that payment by ID
    Then the response status code is 403

  Scenario: Admin can fetch any user's payment by ID
    Given "user" has created a payment
    When I am authenticated as "admin"
    And I GET that payment by ID
    Then the response status code is 200
