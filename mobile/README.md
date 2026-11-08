# VMarket Mobile

Ứng dụng di động **React Native (Expo SDK 58, expo-router, TypeScript)** cho VMarket: app **người mua** và phân hệ **Shipper** (hiển thị theo vai trò đăng nhập, không phải app riêng). Thiết kế: Figma, trang **Mobile**. Ticket: PBL6-72.

## Chạy nhanh

```powershell
# 1) Hạ tầng + backend (từ thư mục gốc repo)
docker compose up -d postgres mongo mongo-init-replica redis rabbitmq
powershell -ExecutionPolicy Bypass -File scripts\start-backend-local.ps1   # 11 service Java, log ở .logs\
# dữ liệu demo (1 lần): xem docs/LOCAL_BACKEND.md

# 2) App
cd mobile
npm install
npx expo start --web --port 19006        # bản web:  http://localhost:19006
npx expo start --android --port 19006    # máy ảo Android (Expo Go tự cài)
```

> Cổng 8081 là cổng mặc định của Metro **và** của auth-service; script backend chạy auth-service ở **18081** (gateway tự trỏ tới). Metro dùng 19006.

Địa chỉ API (`src/lib/config.ts`): Android emulator `http://10.0.2.2:8080`, web/iOS simulator `http://localhost:8080`, điện thoại thật dùng IP máy chủ Metro. Ghi đè bằng `EXPO_PUBLIC_API_BASE_URL`.

- **iPhone thật**: `localhost` trỏ về chính điện thoại nên bắt buộc đặt `EXPO_PUBLIC_API_BASE_URL=http://<IP máy chạy gateway>:8080` (cùng mạng Wi-Fi).
- **Bản build release Android** chặn HTTP không mã hóa: dùng gateway HTTPS hoặc bật `usesCleartextTraffic` qua `expo-build-properties` (chỉ cho bản thử nội bộ).
- Chỉ dùng HTTP thuần trong dev; production phải đặt HTTPS.

## Tài khoản demo (chỉ môi trường local)

| Vai trò | Email | Mật khẩu |
|---|---|---|
| Người mua | `buyer.demo@vmarket.test` | `Buyer@12345` |
| Shipper | `shipper.demo@vmarket.test` | `Shipper@12345` |
| Seller (dữ liệu seed) | `seller.<mã-shop>@seed.vmarket.test` | `Seed@12345` |

Mã OTP in ra log auth-service (`.logs\auth-service.log`, dòng `[DEV EMAIL]`) vì `AUTH_EMAIL_PROVIDER=log`.

## Cấu trúc

```
src/app/(auth)      đăng nhập, đăng ký, OTP, quên/đặt lại mật khẩu
src/app/(buyer)     trang chủ, tìm kiếm, sản phẩm, tìm bằng ảnh, giỏ hàng, thanh toán, đơn hàng,
                    trả hàng, đánh giá, tài khoản, trợ lý AI, thông báo
src/app/(shipper)   đơn được giao, lấy hàng, giao/thất bại/giao lại, COD, hoàn hàng, GPS, lịch sử
src/components/ui   bộ UI dùng chung theo design system (docs/FOUNDATION.md)
src/api, src/features, src/auth, src/lib
```

Xác thực: access token trong bộ nhớ, **refresh token trong SecureStore** (native). Backend trả refresh token trong body/header cho client mobile khi có `X-Client-Type: mobile` và không có `Origin` (auth-service, "Option B"); web dùng cookie HttpOnly như trước.

## Kiểm tra chất lượng

```powershell
npx tsc --noEmit
npx expo lint
npx prettier --check src
```

## Hạn chế đã biết

- **Thanh toán PayOS** cần khóa PayOS + URL công khai cho webhook; chưa thử được ở local (COD chạy đủ luồng).
- **AI** (tìm bằng ảnh, gợi ý, chatbot) cần ai-search/recommendation/chatbot-service + Elasticsearch; không chạy thì app hiện trạng thái lỗi trung thực / tự chuyển sang tìm trong product-service.
- **Ảnh đại diện** và **ảnh minh chứng trả hàng**: backend chưa có API upload (minh chứng gửi dưới dạng data URI nén; ảnh đại diện báo "Chưa cập nhật được ảnh").
- **Đăng nhập Google**: backend chưa có endpoint.
- **Push (FCM)** không hoạt động trong Expo Go trên Android; cần development build. **GPS nền** của Shipper cũng cần development build (Expo Go chỉ gửi vị trí khi app ở foreground).
- Chưa build iOS (cần macOS hoặc EAS Build).
- Backend không tách đơn theo gian hàng, không có giảm giá/phí giao: các màn hình chỉ hiển thị dữ liệu thật.
