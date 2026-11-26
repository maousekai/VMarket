# VMarket Mobile (PBL6-72)

Ứng dụng Android/iOS bằng **React Native + Expo** (SDK 58, JavaScript, Expo Router). Một app duy nhất:
tài khoản `SHIPPER` vào phân hệ giao hàng, các tài khoản khác vào giao diện người mua.

## Chạy thử

```bash
cd mobile
npm install
cp .env.example .env.local      # sửa EXPO_PUBLIC_API_URL trỏ tới API Gateway
npx expo start                  # quét QR bằng Expo Go, hoặc nhấn a để mở Android emulator
```

Backend: `docker compose up -d` ở thư mục gốc rồi chạy gateway + auth + delivery (`scripts\vmarket.cmd core`,
`scripts\vmarket.cmd service delivery-service`).

> **GPS chạy nền** cần bản build thật (development build / APK): Expo Go không hỗ trợ background
> location. Trong Expo Go app vẫn gửi GPS khi đang mở.

## Kiểm tra

```bash
npm run lint
npm run format:check
npm test
npm run doctor        # expo-doctor: kiểm tra phiên bản thư viện khớp SDK
```

CI: `.github/workflows/mobile.yml`, chỉ chạy khi `mobile/**` thay đổi.

## Build & phân phối bản demo

Build trên cloud bằng EAS (không cần Android Studio / máy Mac). Đăng nhập một lần: `npx eas-cli@latest login`.

| Mục đích                    | Lệnh                                                    | Kết quả                                   |
| --------------------------- | ------------------------------------------------------- | ----------------------------------------- |
| APK Android cho buổi bảo vệ | `npx eas-cli@latest build -p android --profile preview` | File `.apk` cài trực tiếp                 |
| Dev build (Android/iOS)     | `npx eas-cli@latest build --profile development`        | App dev client, chạy với `npx expo start` |
| Android local (Windows)     | `npx expo run:android`                                  | Cần Android Studio + SDK                  |

Trước khi build `preview`, sửa `EXPO_PUBLIC_API_URL` trong `eas.json` thành Gateway công khai (HTTPS —
Android chặn HTTP thường ở bản release).

iOS: chạy qua Expo Go (luồng không cần GPS nền) hoặc dev build cài nội bộ
(`--profile development`, cần đăng ký UDID thiết bị). TestFlight không dùng vì cần tài khoản Apple
Developer trả phí.

Thiết bị: Android 8.0+ đáp ứng được. **iOS:** Expo SDK 58 / React Native 0.88 yêu cầu tối thiểu
iOS 15.1, nên yêu cầu "iOS 13+" trong ticket không đạt được với công nghệ đã chốt.

## Cấu trúc

```
src/
  app/                 # Expo Router: mỗi file là một màn hình
    _layout.jsx        # chọn giao diện theo vai trò (Stack.Protected)
    login.jsx
    (buyer)/           # tab người mua (bổ sung ở PR tiếp theo)
    (shipper)/         # tab shipper
  api/                 # client axios qua Gateway (JWT + tự refresh), API từng service
  auth/                # AuthProvider, token trong SecureStore
  shipper/             # danh sách chuyến, cập nhật trạng thái, GPS (foreground + background task)
  components/, theme/
```

### Xác thực

auth-service chỉ trả refresh token qua cookie HttpOnly `refresh_token`. App đọc header `Set-Cookie`,
lưu access + refresh token vào **SecureStore** (Keychain / Keystore) và gửi lại bằng header `Cookie`
khi gọi `/api/auth/refresh`. Một 401 → refresh đúng một lần cho mọi request đang chờ rồi phát lại;
refresh bị từ chối → đăng xuất.
