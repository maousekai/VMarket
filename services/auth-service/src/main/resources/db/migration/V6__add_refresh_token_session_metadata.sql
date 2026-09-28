-- =============================================================================
-- PBL6-46 (FR-AUTH-06) - Quan ly phien: liet ke / thu hoi tung thiet bi.
--
-- "Phien" = dong refresh_tokens con hieu luc (revoked_at IS NULL) cua user do -
-- xoay vong tao dong MOI (khong doi id), nen chi dong con hieu luc moi xuat
-- hien trong danh sach (dong cu bi thu hoi tu dong bien mat, khong can khai
-- niem session_id rieng). user_agent/ip_address la best-effort, chi de hien
-- thi cho nguoi dung, khong dung cho quyet dinh bao mat.
--
-- last_used_at de NULLABLE (khong SET NOT NULL) theo dung quy uoc expand-contract
-- cua du an (services/CLAUDE.md): trong cua so rolling deploy, instance auth-service
-- CU (entity chua co truong nay) van INSERT duoc dong moi ma khong dien cot nay ->
-- neu bat NOT NULL ngay trong migration nay, INSERT do se vi pham constraint va
-- login/refresh sap o instance cu. Code moi luon tu dien lastUsedAt khi tao/xoay
-- vong token (xem TokenIssuer, AuthenticationService.refresh) nen gia tri NULL chi
-- co the xuat hien tu instance cu trong luc deploy - repository xu ly bang COALESCE.
-- =============================================================================

ALTER TABLE refresh_tokens ADD COLUMN user_agent   VARCHAR(255);
ALTER TABLE refresh_tokens ADD COLUMN ip_address   VARCHAR(45);
ALTER TABLE refresh_tokens ADD COLUMN last_used_at TIMESTAMP WITH TIME ZONE;

-- Du lieu cu (neu co) chua co last_used_at -> dung created_at lam gia tri khoi tao.
UPDATE refresh_tokens SET last_used_at = created_at WHERE last_used_at IS NULL;

-- Liet ke phien dang hoat dong cua mot user, moi nhat truoc (loc revoked_at o
-- tang query; khong dung partial index de chay duoc tren ca H2 lan Postgres,
-- giong tien le da chon o V5).
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id, last_used_at);
