-- =============================================================================
-- Review PR 68 lan 3, phan 2: ca lam viec cua shipper.
-- Tu dong phan cong chi chon shipper dang trong ca; shipper bam "Bat dau ca" / "Ket thuc ca".
-- Shipper hien co mac dinh ngoai ca (phai bam "Bat dau ca" moi duoc giao don moi).
-- Cu phap chay duoc tren PostgreSQL va H2.
-- =============================================================================
ALTER TABLE shippers ADD COLUMN on_shift BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shippers ADD COLUMN shift_changed_at TIMESTAMP WITH TIME ZONE;
