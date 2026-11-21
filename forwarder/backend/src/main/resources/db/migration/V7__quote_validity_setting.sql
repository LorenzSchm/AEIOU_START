-- Commercial validity of a quote in days (see QuoteSender): the portal's 15-minute rate_id is only our booking handle.
INSERT INTO setting (key, value, updated_by) VALUES
    ('quoteValidityDays', '7', 'migration')
ON CONFLICT (key) DO NOTHING;
