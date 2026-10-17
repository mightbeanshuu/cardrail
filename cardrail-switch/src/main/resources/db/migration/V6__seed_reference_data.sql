INSERT INTO stand_in_limit (bin_prefix, currency, max_amount_minor) VALUES
    ('',       356, 100000),   -- any card, INR 1,000.00
    ('5',      356, 250000),   -- Mastercard 5-series, INR 2,500.00
    ('555555', 356, 500000),   -- example premium product range, INR 5,000.00
    ('',       840, 2500),     -- any card, USD 25.00
    ('5',      840, 5000);     -- Mastercard 5-series, USD 50.00

INSERT INTO mcc_rule (mcc, category, blocked) VALUES
    ('5411', 'GROCERY', false),
    ('5812', 'RESTAURANTS', false),
    ('5541', 'FUEL', false),
    ('4111', 'TRANSIT', false),
    ('4511', 'AIRLINES', false),
    ('4900', 'UTILITIES', false),
    ('5732', 'ELECTRONICS', false),
    ('5999', 'RETAIL_OTHER', false),
    ('7995', 'GAMBLING', true),    -- betting, lotteries, casino chips
    ('7801', 'GAMBLING', true),    -- government-licensed online casinos
    ('7802', 'GAMBLING', true);    -- government-licensed horse/dog racing
