-- V13: a payment form carries a one-time request id, so a double-click or a re-sent form records ONE payment.
-- Same idea as sales_order.client_request_id (V8). NULL for payments recorded without a form (allowed many times);
-- the UNIQUE constraint is the last line of defence if two identical requests arrive at the same moment.
ALTER TABLE payment ADD COLUMN client_request_id VARCHAR(60) UNIQUE;
