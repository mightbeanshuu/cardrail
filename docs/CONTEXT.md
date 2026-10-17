# Context: what a card switch does, and why these pieces

This is the background the design leans on. Every external fact links to a public source.

## The authorization path

A card payment is two separate flows. **Authorization** happens in real time while the customer waits:
the acquirer (the merchant's bank) sends an ISO 8583 `0100` request through the network to the issuer
(the cardholder's bank), which approves or declines and places a hold. **Clearing and settlement**
happen later in batches: captured transactions are exchanged between banks and net positions are
settled. ISO 8583 defines the messages: the MTI, a bitmap of which data elements are present, and the
elements themselves (DE2 PAN, DE4 amount, DE39 response code).
Reference: https://en.wikipedia.org/wiki/ISO_8583

Card messaging still runs on ISO 8583 while real-time account-to-account payments use ISO 20022.
Mastercard's CEO described it as "cards using ISO 8583… messaging technology, which is 50 years old,
then real-time payments became real with ISO 20022."
https://www.forbes.com/sites/ninabambysheva/2023/02/07/how-mastercard-goldman-sachs-and-other-tradfi-titans-are-using-blockchain-to-rewire-global-finance/

## Why each piece exists

| Problem in a real network | What cardrail does about it |
|---|---|
| Terminals retransmit when a response is slow, and the same request can arrive twice | Duplicate detection on acquirer + STAN + transmission time + terminal; replay or `94` |
| The issuer can be slow or down, but the cardholder is standing at the till | Time limiter and circuit breaker; stand-in processing within conservative limits, with an advice for the issuer |
| Card numbers are the most sensitive data in the system | Tokenization: stored records carry a token and a masked PAN, the PAN itself is envelope-encrypted |
| Money must never be created or lost by a bug or a race | Double-entry ledger, balance enforced in the database, row locks on the account |
| Downstream systems (clearing, fraud, reporting) need every event exactly once in effect | Transactional outbox: the event commits with the state change, consumers dedupe |
| Auditors need to trust the history | Append-only, hash-chained audit log with a verification endpoint |

## Tokenization

Network tokenization replaces the PAN with a token restricted to a device, wallet or merchant, and the
token is used in transactions instead of the PAN (Mastercard Digital Enablement Service:
https://developer.mastercard.com/product/mdes/). India moved card-on-file payments to tokens under
RBI rules; Mastercard's CEO called it "the first pretty much completely tokenized market in the world"
(https://www.cnbctv18.com/business/mastercard-global-ceo-indias-fintech-boom-michael-miebach-interview-19493718.htm).
`cardrail-vault` models the vault mechanics only: real network tokens come from network-assigned token
ranges with domain restrictions.

## API security on payment APIs

Mastercard's public APIs authenticate requests with OAuth 1.0a signatures (RSA-SHA256 with an
`oauth_body_hash`, so the body is signed too) and protect sensitive fields with JWE field-level
encryption. Both client libraries are open source:
https://github.com/Mastercard/oauth1-signer-java and https://github.com/Mastercard/client-encryption-java.
`cardrail-apisec` implements the receiving side and is tested against those libraries.

## Where this kind of work happens

Mastercard's Pune tech hub opened in October 2024 for 6,000+ technologists, focused on "payment
safety, cybersecurity, fraud detection, and digital identity"
(https://www.mastercard.com/news/ap/en/newsroom/press-releases/en/2024/mastercard-opens-new-state-of-the-art-tech-hub-in-pune-india/).
Its CTO has said Pune contributed to the card tokenization technology behind Apple Pay and Android Pay
(https://timesofindia.indiatimes.com/technology/times-techies/in-punes-tech-hub-mastercard-forges-its-ai-powered-future/articleshow/114997601.cms).
In a sample of 122 Pune engineering postings read from Mastercard's careers site in October 2026, the
most frequent technologies were Java (80), Spring (51), SQL (45), AWS (42), REST (40), Kubernetes (37),
Oracle (33), microservices (29) and Kafka (23). That is the stack this project is built on.
