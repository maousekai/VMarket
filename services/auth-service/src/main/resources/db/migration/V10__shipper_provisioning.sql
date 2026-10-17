CREATE TABLE shipper_provisions (
    provision_key VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(26) NOT NULL UNIQUE REFERENCES users(id)
);
