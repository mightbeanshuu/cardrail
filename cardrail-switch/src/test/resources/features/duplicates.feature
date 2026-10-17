Feature: Duplicate transmissions
  Terminals resend a request when they do not hear back in time. The cardholder must never be
  charged twice for one purchase.

  Background:
    Given a card with a balance of ₹500.00

  Scenario: The terminal resends the same request
    Given the terminal requested ₹200.00 at MCC 5411 and it was approved
    When the terminal sends the same request again
    Then the response is the same as the first, with the same auth code
    And ₹200.00 is held on the card

  Scenario: A different request reuses the identifiers of an earlier one
    Given the terminal requested ₹200.00 at MCC 5411 and it was approved
    When a different request for ₹250.00 arrives with the same STAN, time and terminal
    Then the response code is 94
    And ₹200.00 is held on the card
