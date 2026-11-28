-- Review PR 68 lan 2, muc 4.10: toa do diem giao nguoi mua chon tren ban do (khong bat buoc).
ALTER TABLE addresses ADD COLUMN latitude DOUBLE PRECISION;
ALTER TABLE addresses ADD COLUMN longitude DOUBLE PRECISION;
