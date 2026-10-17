-- ILLUSTRATIVE rates only, not the published interchange of any card network. Real interchange is
-- set per network, region, card product, merchant program and channel, and changes over time.
-- The fixed fee is in the minor units of the transaction currency.

INSERT INTO interchange_rate (category, rate_bps, fixed_fee_minor, description)
    VALUES ('DEFAULT', 175, 0, 'Illustrative: any MCC without a category');
INSERT INTO interchange_rate (category, rate_bps, fixed_fee_minor, description)
    VALUES ('GROCERY', 110, 0, 'Illustrative: grocery stores and supermarkets');
INSERT INTO interchange_rate (category, rate_bps, fixed_fee_minor, description)
    VALUES ('FUEL', 50, 500, 'Illustrative: fuel dispensers and service stations');
INSERT INTO interchange_rate (category, rate_bps, fixed_fee_minor, description)
    VALUES ('RESTAURANT', 150, 0, 'Illustrative: restaurants and fast food');
INSERT INTO interchange_rate (category, rate_bps, fixed_fee_minor, description)
    VALUES ('TRAVEL', 190, 1000, 'Illustrative: airlines');

INSERT INTO mcc_interchange_category (mcc, category) VALUES ('5411', 'GROCERY');
INSERT INTO mcc_interchange_category (mcc, category) VALUES ('5541', 'FUEL');
INSERT INTO mcc_interchange_category (mcc, category) VALUES ('5542', 'FUEL');
INSERT INTO mcc_interchange_category (mcc, category) VALUES ('5812', 'RESTAURANT');
INSERT INTO mcc_interchange_category (mcc, category) VALUES ('5814', 'RESTAURANT');
INSERT INTO mcc_interchange_category (mcc, category) VALUES ('4511', 'TRAVEL');
