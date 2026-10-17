Feature: Stand-in processing
  When the issuer does not answer in time, the switch decides on the issuer's behalf. Because the
  switch carries that risk, it only approves small amounts, and it tells the issuer afterwards.

  Background:
    Given a card with a balance of ₹50,000.00
    And the stand-in limit for cards starting 555555 is ₹5,000.00

  Scenario: A small purchase is approved in stand-in
    Given the issuer is not responding
    When the terminal requests ₹1,000.00 at MCC 5411
    Then the response code is 00
    And the decision was made in stand-in
    And a stand-in advice is queued for the issuer
    And nothing is held on the card

  Scenario: A purchase above the stand-in limit is declined
    Given the issuer is not responding
    When the terminal requests ₹6,000.00 at MCC 5411
    Then the response code is 91
    And the decision was made in stand-in
    And a stand-in advice is queued for the issuer

  Scenario: An issuer that keeps failing is cut off
    Given the issuer is failing every request
    When the terminal sends 5 requests of ₹100.00 at MCC 5411
    Then the switch stops calling the issuer
