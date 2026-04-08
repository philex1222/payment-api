Feature: Authentication API
  In order to securely access the Payment API
  As a registered user
  I want to authenticate with my credentials and manage my session

  Background:
    Given the API is available

  Scenario: Successful login returns a JWT token
    When I login as "admin" with password "password"
    Then the response status code is 200
    And the response contains a valid JWT token

  Scenario: Login with wrong password is rejected
    When I login as "admin" with password "incorrectpassword"
    Then the response status code is 401
    And the error message contains "Invalid username or password"

  Scenario: Login with wrong username is rejected
    When I login as "doesnotexist" with password "password"
    Then the response status code is 401
    And the error message contains "Invalid username or password"

  Scenario: Accessing a protected endpoint without a token returns 401
    When I GET "/api/v1/payments" without authentication
    Then the response status code is 401

  Scenario: Authenticated user can view their own profile
    Given I am authenticated as "admin"
    When I request my profile
    Then the response status code is 200
    And the profile username is "admin"
    And the profile has a "role" field
    And the profile has a "createdAt" field

  Scenario: Logout blacklists the token
    Given I am authenticated as "user"
    When I logout
    Then the response status code is 200
    When I GET "/api/v1/auth/me" with the invalidated token
    Then the response status code is 401
