-- RFQ -> quote workflow (package rfq).

-- The customer mail we reply to, and the one-time decision claim (decide is idempotent across timers and bids).
ALTER TABLE rfq ADD COLUMN mail_id BIGINT REFERENCES mail (id);
ALTER TABLE rfq ADD COLUMN decided_at TIMESTAMPTZ;

-- The rate desk (ratedesk@hackloop.mail) is not on the carrier list, so its requests have no carrier row.
ALTER TABLE bid_request ALTER COLUMN carrier_id DROP NOT NULL;
ALTER TABLE bid_request ADD COLUMN to_addr TEXT;
