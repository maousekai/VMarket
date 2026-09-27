-- =============================================================================
-- PBL6-14 - Nhat ky sua noi dung gian hang (ra soat PR #24, muc A).
--
-- VAN DE: FR-SHOP-02 cho nguoi ban tu sua thong tin va KHONG dua gian hang ve
-- "Cho duyet" sau moi lan sua (dua ve se lam gian hang bien mat khoi nguoi mua
-- vi mot lan sua mo ta - xem worklog PBL6-14 muc 1.8). Hau qua: mot ho so da
-- duoc duyet voi noi dung sach co the bi doi sang noi dung vi pham ma van
-- ACTIVE, va Admin khong co dau vet nao de biet.
--
-- CACH XU LY: khong chan sua, nhung moi truong bi doi ghi mot dong vao day
-- (gia tri cu / gia tri moi / trang thai luc sua / ai sua). Admin doc qua
-- GET /api/shops/admin/{id}/profile-history de doi chieu voi ho so da duyet,
-- va dinh chi neu can (FR-SHOP-04).
--
-- Quy uoc cot giong V1: ULID VARCHAR(26), TIMESTAMP WITH TIME ZONE dang chuan
-- SQL (khong dung bi danh TIMESTAMPTZ) de chay duoc ca tren H2 o
-- FlywayMigrationTest.
-- =============================================================================

CREATE TABLE shop_profile_changes (
    id               VARCHAR(26)   PRIMARY KEY,
    shop_id          VARCHAR(26)   NOT NULL REFERENCES shops (id) ON DELETE CASCADE,
    -- Ten truong trong ho so: name, description, logoUrl, policies...
    field_name       VARCHAR(40)   NOT NULL,
    -- Dai bang cot dai nhat cua shops (policies VARCHAR(5000)) de khong bao gio
    -- phai cat bot gia tri khi luu vet.
    old_value        VARCHAR(5000),
    new_value        VARCHAR(5000),
    -- Trang thai gian hang NGAY LUC sua: sua khi dang ACTIVE la truong hop Admin
    -- can soi, sua khi PENDING/REJECTED thi ho so con phai qua kiem duyet.
    status_at_change VARCHAR(20)   NOT NULL,
    changed_by       VARCHAR(26)   NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now()
);

-- Doc theo gian hang, moi nhat truoc.
CREATE INDEX idx_shop_profile_changes_shop_id ON shop_profile_changes (shop_id, created_at);
