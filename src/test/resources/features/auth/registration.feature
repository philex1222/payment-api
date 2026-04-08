Feature: User Registration
  In order to create an account and access the Payment API
  As a new user
  I want to register with a unique username and a strong password

  Scenario: Successful registration creates a ROLE_USER account
    Given a unique registration username
    When I register with that username and password "SecurePass1"
    Then the response status code is 201
    And the response username matches the registered username
    And the response role is "ROLE_USER"
    And the response has a "createdAt" timestamp
    And the new user can login immediately

  Scenario: Registering an already-taken username returns 409
    When I register with username "admin" and password "SecurePass1"
    Then the response status code is 409
    And the error is "Username Already Taken"
    And the error message does not contain "admin"

  Scenario Outline: Registration input validation
    When I register with username "<username>" and password "<password>"
    Then the response status code is 400
    Examples:
      | username      | password        | reason                     |
      | ab            | SecurePass1     | username too short (< 3)   |
      | bddtestuser99 | short           | password too short (< 8)   |
      | bddtestuser99 | alllowercase1   | password missing uppercase |
      | bddtestuser99 | AllNoDigitHere  | password missing digit     |
      | bad user!     | SecurePass1     | invalid username characters |
