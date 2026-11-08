-- product-service identifies variants with a UUID (36 chars), not a 26-char ULID.
-- Without this, creating an order for any product with variants fails with
-- "value too long for type character varying(26)".
ALTER TABLE order_items        ALTER COLUMN variant_id TYPE VARCHAR(64);
ALTER TABLE order_return_items ALTER COLUMN variant_id TYPE VARCHAR(64);
