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

Theo dõi đơn trên app lấy trạng thái và vé qua API Gateway, rồi kết nối WebSocket qua web VMarket
(nginx hoặc Vite proxy). Đặt `EXPO_PUBLIC_WEB_URL` thành URL mà thiết bị truy cập được và cho phép
origin tương ứng trong `CORS_ALLOWED_ORIGINS` của delivery-service. Khi proxy WebSocket ở host riêng,
dùng `EXPO_PUBLIC_DELIVERY_WS_ORIGIN`. Một URL WebSocket tuyệt đối do delivery-service trả về được
ưu tiên. Bản phát hành dùng HTTPS và WSS.

> **GPS chạy nền** cần bản build thật (development build / APK): Expo Go không hỗ trợ background
> location. Trong Expo Go app vẫn gửi GPS khi đang mở.

### Giới hạn GPS khi tắt màn hình (ROM Trung Quốc)

`expo-task-manager` chạy phần JS của GPS nền bằng một `JobService`. Các ROM Xiaomi (MIUI/HyperOS),
Oppo/Realme (ColorOS), Vivo (OriginOS) và Meizu (Flyme) chặn job này khi màn hình tắt, kể cả khi app
đã được miễn tối ưu pin. Máy vẫn lấy được vị trí, nhưng app không gửi được về server. Đã kiểm tra
trên Meizu 20 (Android 16).

Cách app xử lý:

- Khi đang có chuyến cần GPS (đã lấy hàng hoặc đang hoàn hàng), app **giữ màn hình sáng**
  (`expo-keep-awake`) và nhả ra khi hết chuyến.
- Nếu hơn 90 giây server không nhận được điểm nào, banner **"Mất kết nối GPS"** hiện ở tab Đơn giao,
  màn chi tiết và tab Vị trí, kèm nút **Mở cài đặt ứng dụng**.
- Tab **Vị trí** hướng dẫn shipper bật "Cho phép chạy nền / Tự khởi động" và tắt tối ưu pin cho
  VMarket.

Hướng dẫn cho shipper khi demo: để app mở trong lúc giao. Muốn GPS chạy khi tắt màn hình thì cần một
module native gửi vị trí ngay trong foreground service, không qua JobScheduler. Phần này chưa làm.

### Chế độ tối

App có theme sáng và tối thật (`src/theme`, theo `useColorScheme`). Theme Android là `DayNight` với
`forceDarkAllowed=false` (`plugins/with-day-night-android-theme.js`), để ROM không tự đảo màu đè lên
theme tối của app. Cấu hình này cần build lại bản native.

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

Trước khi build, đặt `EXPO_PUBLIC_API_URL` và `EXPO_PUBLIC_WEB_URL` trong môi trường EAS
`preview` (APK demo) hoặc `production` (bản phát hành). Dùng URL **HTTPS** thật của API Gateway
và trang web, với mức hiển thị `Plain text` vì các giá trị này được nhúng trong app.
Build `preview` và `production` sẽ báo lỗi nếu thiếu URL hợp lệ.

iOS: chạy qua Expo Go (luồng không cần GPS nền) hoặc dev build cài nội bộ
(`--profile development`, cần đăng ký UDID thiết bị). TestFlight không dùng vì cần tài khoản Apple
Developer trả phí.

Thiết bị: Android 8.0+ đáp ứng được. **iOS:** Expo SDK 58 / React Native 0.88 yêu cầu tối thiểu
iOS 15.1, nên yêu cầu "iOS 13+" trong ticket không đạt được với công nghệ đã chốt.

## Cấu trúc

```
src/
  app/                       # Expo Router: mỗi file là một màn hình
    _layout.jsx              # theme sáng/tối + chọn giao diện theo vai trò (Stack.Protected)
    login.jsx, register.jsx, forgot-password.jsx
    (buyer)/_layout.jsx      # Stack bọc nhóm tab và các màn chi tiết
      (tabs)/                # Trang chủ / Giỏ hàng / Tài khoản
      search, product/[id], checkout, payment/[orderId], order-success,
      orders/, delivery/[orderId], notifications, profile, password, addresses/
    (shipper)/_layout.jsx    # ShipperProvider (chuyến + GPS chung) + Stack
      (tabs)/                # Đơn giao / Vị trí / Lịch sử
      assignment/[id], notifications
  api/                       # client axios qua Gateway (JWT + tự refresh), API từng service
  auth/                      # AuthProvider, token trong SecureStore
  buyer/                     # màn người mua: mua hàng, giỏ, VNPay/COD, đơn, theo dõi, tài khoản
  shipper/                   # chuyến, các bước giao, GPS (foreground + background task), ca làm việc
  components/ui.jsx          # Header, Button, Input, Card, Bottom navigation theo design system Figma
  theme/                     # token màu sáng/tối, makeStyles
```

Không đặt file test trong `src/app/`: Expo Router coi mọi file ở đó là một route.

Giao diện theo Figma (trang Mobile, frame `Mobile / UC-xx / ...`). Những chỗ khác Figma để theo
nghiệp vụ đã chốt:

- **Thanh toán:** Figma ghi PayOS, nhưng backend dùng **VNPay**. App mở trang VNPay trong WebView,
  bắt URL `/payment/vnpay-return` và gửi cho payment-service xác thực.
- **Phí giao:** ghi "Miễn phí".
- **Ca làm việc:** thanh ca nằm ở đầu tab Đơn giao.
- **Lấy và trả hàng:** làm theo từng shop.
- **Đã lược bỏ** (ẩn hẳn nút): tìm bằng ảnh, trợ lý AI, trả hàng, đánh giá, đổi ảnh đại diện,
  đăng nhập Google.

### Xác thực

auth-service chỉ trả refresh token qua cookie HttpOnly `refresh_token`. App đọc header `Set-Cookie`,
lưu access + refresh token vào **SecureStore** (Keychain / Keystore) và gửi lại bằng header `Cookie`
khi gọi `/api/auth/refresh`. Một 401 → refresh đúng một lần cho mọi request đang chờ rồi phát lại;
refresh bị từ chối → đăng xuất.
