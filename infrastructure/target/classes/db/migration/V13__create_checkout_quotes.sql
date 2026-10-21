CREATE TABLE checkout_quotes (
    quote_id UUID PRIMARY KEY,
    principal_id BIGINT NOT NULL,
    pricing_input_fingerprint VARCHAR(64) NOT NULL,
    pricing_fingerprint VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_order_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_checkout_quotes_principal_expiry
    ON checkout_quotes (principal_id, expires_at);
CREATE INDEX idx_checkout_quotes_expiry ON checkout_quotes (expires_at);
