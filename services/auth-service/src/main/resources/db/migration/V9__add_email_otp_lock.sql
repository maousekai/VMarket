-- =============================================================================
-- PBL6-47 (review PR) - Tuan tu hoa viec phat OTP theo email (FR-AUTH-01).
--
-- requestOtp kiem cooldown + tran theo gio roi moi INSERT email_otp. Khong co gi
-- khoa giua hai buoc -> 2 request song song cung thay 4 ma cu va cung chen, vuot
-- tran 5 ma/gio. Khong khoa duoc dong users vi email xin OTP co the CHUA co tai
-- khoan -> moi email mot dong o day: OtpIssuer tao dong (transaction rieng, bo
-- qua neu trung) roi SELECT ... FOR UPDATE dong do trong cung transaction voi
-- kiem tra + INSERT email_otp.
--
-- Bang moi, chi them, khong dong vao du lieu cu - an toan khi chay luc cao diem.
-- Rollback: DROP TABLE email_otp_lock; (khong mat du lieu gi co gia tri - bang chi
--   giu khoa, OtpCleanupJob tu don dong cua email khong con OTP).
-- =============================================================================

CREATE TABLE email_otp_lock (
    email      VARCHAR(320) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
