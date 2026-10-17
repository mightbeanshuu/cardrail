Feature: Card authorization
  A cardholder pays at a terminal. The switch checks the request, asks the card's issuer, and on
  approval holds the amount on the card until the merchant captures it.

  Background:
    Given a card with a balance of ₹500.00

  Scenario: An approved purchase holds the amount
    When the terminal requests ₹200.00 at MCC 5411
    Then the response code is 00
    And ₹200.00 is held on the card
    And ₹300.00 is available on the card

  Scenario: A purchase larger than the balance is declined
    When the terminal requests ₹500.01 at MCC 5411
    Then the response code is 51
    And nothing is held on the card
    And ₹500.00 is available on the card

  Scenario: Betting merchants are blocked
    When the terminal requests ₹100.00 at MCC 7995
    Then the response code is 57
    And nothing is held on the card

  Scenario: An expired card is declined
    When the terminal requests ₹100.00 at MCC 5411 with a card expiring 2001
    Then the response code is 54

  Scenario: The merchant captures less than was authorized
    Given the terminal requested ₹200.00 at MCC 5411 and it was approved
    When the merchant captures ₹150.00
    Then ₹150.00 is owed to the merchant
    And nothing is held on the card
    And ₹350.00 is available on the card
