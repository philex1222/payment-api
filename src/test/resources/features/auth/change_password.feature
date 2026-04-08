Feature: Change Password
  In order to maintain account security
  As an authenticated user
  I want to be able to change my password

  Scenario: Changing password with the correct current password succeeds
    Given I have registered a fresh user
    When I change my password from "SecurePass1" to "NewPassword1"
    Then the response status code is 200
    And I can login with the new password "NewPassword1"

  Scenario: Changing password with the wrong current password is rejected
    Given I have registered a fresh user
    When I change my password from "WrongPass1" to "NewPassword1"
    Then the response status code is 401

  Scenario: New password that is too short is rejected
    Given I have registered a fresh user
    When I change my password from "SecurePass1" to "short"
    Then the response status code is 400

  Scenario: Unauthenticated password change is rejected
    Given I am not authenticated
    When I POST to "/api/v1/auth/change-password" with body:
      """
      {"currentPassword":"password","newPassword":"NewPassword1"}
      """
    Then the response status code is 401
