-- =============================================================================
-- PBL6-30 - Order Service: id san pham / bien the tu Product Catalog.
--
-- product-service dung ObjectId MongoDB (24 ky tu) cho san pham va UUID (36 ky tu)
-- cho bien the, khong phai ULID 26 ky tu nhu id noi bo cua order-service. Cot
-- VARCHAR(26) lam moi don co bien the that loi "value too long" khi dat hang.
-- Noi rong len 64 cho ca dong hang va dong tra hang.
--
-- SET DATA TYPE chay duoc tren ca PostgreSQL va H2 (FlywayMigrationTest).
-- =============================================================================

ALTER TABLE order_items ALTER COLUMN product_id SET DATA TYPE VARCHAR(64);
ALTER TABLE order_items ALTER COLUMN variant_id SET DATA TYPE VARCHAR(64);
ALTER TABLE order_return_items ALTER COLUMN product_id SET DATA TYPE VARCHAR(64);
ALTER TABLE order_return_items ALTER COLUMN variant_id SET DATA TYPE VARCHAR(64);
