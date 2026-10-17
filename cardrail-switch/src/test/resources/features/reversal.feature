Feature: Reversal
  If a sale does not go through after approval (the terminal timed out, the customer cancelled),
  the acquirer reverses it and the held money goes back to the cardholder.

  Background:
    Given a card with a balance of ₹500.00

  Scenario: A reversal releases the hold
    Given the terminal requested ₹200.00 at MCC 5411 and it was approved
    When the acquirer reverses the authorization
    Then nothing is held on the card
    And ₹500.00 is available on the card

  Scenario: A reversal sent twice releases the money only once
    Given the terminal requested ₹200.00 at MCC 5411 and it was approved
    When the acquirer reverses the authorization
    And the acquirer reverses the authorization again
    Then both reversals report ₹200.00 released
    And ₹500.00 is available on the card

  Scenario: Reversing a declined purchase changes nothing
    Given the terminal requested ₹600.00 at MCC 5411 and it was declined with 51
    When the acquirer reverses the authorization
    Then the reversal reports nothing to reverse
    And ₹500.00 is available on the card
