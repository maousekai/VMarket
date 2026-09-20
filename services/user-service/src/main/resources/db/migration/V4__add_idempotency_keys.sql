-- =============================================================================
-- PBL6-13 - User Service: ho tro header Idempotency-Key cho cac endpoint ghi.
--
-- VI SAO CAN: client het thoi gian cho roi gui lai la chuyen binh thuong (mang
-- yeu, bam hai lan, retry tu dong cua thu vien HTTP). Voi POST /api/users/me/
-- addresses, lan gui lai tao them mot dia chi y het - khong loi nao bao ra, nguoi
-- dung tu phat hien va tu don. Bang nay nho lai "key nay da xu ly roi, ket qua la
-- day" de phat lai thay vi lam lai.
--
-- Theo dung quy uoc cua V1/V2: khoa chinh ULID VARCHAR(26), thoi gian
-- TIMESTAMP WITH TIME ZONE (dang chuan SQL) de FlywayMigrationTest chay duoc
-- script nay tren H2.
--
-- DANH SO V4 (khong phai V3): V3 la partial index rieng cua PostgreSQL, nam o
-- db/vendor/postgresql. Tren H2 thi so thu tu nhay 2 -> 4, Flyway chap nhan
-- khoang trong nay.
-- =============================================================================

CREATE TABLE idempotency_keys (
    id                    VARCHAR(26)  PRIMARY KEY,
    -- user_id: pham vi cua mot key. Khong co cot nay thi key trung nhau giua hai
    -- nguoi dung se lam nguoi nay nhan response cua nguoi kia.
    user_id               VARCHAR(26)  NOT NULL,
    idempotency_key       VARCHAR(200) NOT NULL,
    request_method        VARCHAR(10)  NOT NULL,
    request_path          VARCHAR(500) NOT NULL,
    -- SHA-256 (hex) cua method + duong dan + body. Cung key ma khac van tay nghia
    -- la client dung lai key cho mot request khac -> tra 422 thay vi lang le phat
    -- lai ket qua cu va nuot mat request that.
    request_fingerprint   VARCHAR(64)  NOT NULL,
    -- NULL = request dau tien VAN DANG chay. Chinh dong "dang chay" nay (cung voi
    -- rang buoc duy nhat ben duoi) la thu chan request thu hai.
    response_status       INTEGER,
    response_body         VARCHAR(8000),
    response_content_type VARCHAR(100),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at          TIMESTAMP WITH TIME ZONE
);

-- Chot chan that su cua co che: hai request song song cung key thi CSDL tu choi
-- cai thu hai. Kiem tra "da ton tai chua" o tang ung dung khong lam duoc viec nay
-- vi ca hai deu doc thay "chua co".
CREATE UNIQUE INDEX uq_idempotency_user_key ON idempotency_keys (user_id, idempotency_key);

-- Cho viec don dinh ky (IdempotencyService.purgeExpired).
CREATE INDEX idx_idempotency_created_at ON idempotency_keys (created_at);
