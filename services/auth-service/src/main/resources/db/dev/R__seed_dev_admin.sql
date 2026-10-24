-- =============================================================================
-- CHI DUNG CHO DEV / E2E (profile 'dev'): tai khoan ADMIN mau de thu giao dien quan tri.
--
--   Email    : admin@vmarket.local
--   Mat khau : Admin@12345
--   Username : admin.vmarket
--   Vai tro  : ADMIN
--
-- Script nam trong db/dev, thu muc nay CHI duoc nap khi bat profile 'dev'
-- (spring.flyway.locations trong application-dev.yml). application.yml va
-- application-prod.yml khong tro toi db/dev, nen production KHONG co tai khoan nay.
-- DevAdminSeedTest giu rang buoc do.
--
-- Repeatable migration (R__): chay sau moi migration V*, nen khong vuong thu tu phien
-- ban khi them V10, V11... Cau lenh idempotent: neu DB da co tai khoan nay (vd tu
-- tao tay) thi khong them, khong ghi de.
-- =============================================================================

INSERT INTO users (id, email, username, password_hash, email_verified)
SELECT '01JBQ9YDX7K3M8N5P2R4T6V8AA', 'admin@vmarket.local', 'admin.vmarket',
       '$2b$12$DdjGxps0/iO3HBso9hgSYOJ7QsJlECeQ56mq2edL54go9cQVDJ1v2', true
FROM roles r
WHERE r.name = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM users u
      WHERE u.email = 'admin@vmarket.local' OR u.username = 'admin.vmarket'
  );

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.name = 'ADMIN'
WHERE u.email = 'admin@vmarket.local'
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );
