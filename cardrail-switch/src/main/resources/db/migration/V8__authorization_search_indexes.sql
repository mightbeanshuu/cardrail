-- Indexes for GET /v1/authorizations (newest first, offset paging).
--
-- created_at_idx serves the unfiltered list, the from/to range, and the low-cardinality filters
-- (status, response_code, stand_in): the scan runs in result order and stops after one page.
-- merchant_id and last4 are selective, so each gets its own index in the same order.
-- last4 is not stored separately; the expression index matches the query's right(masked_pan, 4).

CREATE INDEX card_authorization_created_at_idx ON card_authorization (created_at DESC, id DESC);
CREATE INDEX card_authorization_merchant_idx ON card_authorization (merchant_id, created_at DESC, id DESC);
CREATE INDEX card_authorization_last4_idx ON card_authorization (right(masked_pan, 4), created_at DESC, id DESC);
