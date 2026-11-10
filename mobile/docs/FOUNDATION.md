# VMarket mobile - foundation reference

Import UI from `@/components/ui`, tokens from `@/theme`, helpers from `@/lib/*`. Never hard-code
colours/sizes; use the tokens. Prettier: no semicolons, single quotes (`npm run format`).
Gates: `npm run typecheck`, `npx expo lint`, `npx expo export --platform web`.

## Example screen (a feature route)

```tsx
// src/app/(buyer)/orders/index.tsx   (path /orders, highlights "Tài khoản")
import { router } from 'expo-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/api/client'
import { AppText, Card, ErrorState, Screen, Skeleton, StatusBadge } from '@/components/ui'
import { useRequireAuth } from '@/auth/useRequireAuth'
import { formatVnd } from '@/lib/format'

export default function OrdersScreen() {
  const ok = useRequireAuth() // pushes /(auth)/login?returnTo=... for guests
  const q = useQuery({ queryKey: ['orders'], queryFn: async () => (await api.get('/api/orders')).data })
  return (
    <Screen title="Đơn hàng" onBack={() => router.back()} refreshing={q.isRefetching} onRefresh={q.refetch}>
      {!ok ? null : q.isPending ? <Skeleton height={80} /> : q.isError ? (
        <ErrorState error={q.error} onRetry={q.refetch} />
      ) : q.data.map((o: any) => (
        <Card key={o.id} onPress={() => router.push(`/orders/${o.id}`)}>
          <AppText variant="label">{o.code}</AppText>
          <StatusBadge label="Chờ xác nhận" tone="warning" />
          <AppText color="primary600">{formatVnd(o.total)}</AppText>
        </Card>
      ))}
    </Screen>
  )
}
```

## Routing and route ownership

- `src/app/index.tsx` redirects: shipper -> `/(shipper)`, PENDING -> `/(auth)/verify-email`
  (that screen is NOT built yet; the auth owner creates it), everyone else (guests too) -> `/(buyer)`.
- Areas: `(auth)` (Stack), `(buyer)` and `(shipper)` (Stack + persistent bottom bar, see Gotchas).
  Buyer tabs: index (Trang chủ), cart (Giỏ hàng), account (Tài khoản). Shipper tabs: index
  (Đơn giao), location (Vị trí), history (Lịch sử).
- **Ownership:** each feature engineer owns distinct folders, e.g. `(buyer)/orders/**`,
  `(buyer)/product/**`, `(buyer)/checkout/**`, `(auth)/**`, `(shipper)/tasks/**`, plus
  `src/features/<name>/**` (non-route code) and `src/api/<name>.ts`. Do not edit another owner's
  folder. Shared files (`src/components/ui`, `src/theme`, `src/navigation/tabs.ts`, layouts) get
  small additive edits only. The placeholder screens (home, cart, account, shipper tabs, login)
  are overwritten by their owners.
- Adding a screen = adding a file. Group folders are not part of the URL: `(buyer)/orders/[id].tsx`
  is `/orders/12` (`router.push('/orders/12')`, `useLocalSearchParams`). Never create the same URL
  in two groups. `(buyer)/index` and `(shipper)/index` are both `/`: navigate with the explicit
  `'/(buyer)'` / `'/(shipper)'` hrefs.
- **Tab highlighting:** add your top-level path to `match` of the owning tab in
  `src/navigation/tabs.ts` (e.g. '/orders' belongs to Tài khoản).
- Guards: `(shipper)` redirects non-shippers; `(buyer)` redirects shippers; `(auth)` bounces
  authenticated + verified users to `?returnTo=` (or their home). `useRequireAuth()` returns true
  when signed in, otherwise pushes `/(auth)/login?returnTo=<path>` and returns false. A login
  screen only calls `login()`; the `(auth)` layout performs the redirect. `safeReturnTo()` in
  `@/lib/safeReturnTo` validates a returnTo value if you need it yourself.

## Components (`@/components/ui`)

All accept `testID`. `?` = optional.

| Component | Props |
|---|---|
| `Screen` | `title`, `role?: 'buyer'\|'shipper'`, `onBack?`, `right?`, `scroll?=true`, `footer?`, `refreshing?`, `onRefresh?`, `contentStyle?`. Header + keyboard-avoiding body (bg subtle, padding 20, gap 12) + fixed footer. Wrap every screen; use `scroll={false}` when you render your own FlatList |
| `AppHeader` | `title`, `role?`, `onBack?` (shows chevron), `right?` |
| `BottomTabBar` | `items: TabItem[]` (`{route: Href, label, match: string[]}`); used by the area layouts only |
| `Button` | `title`, `onPress`, `kind?: primary\|secondary\|accent\|neutral\|danger`, `size?: 'L'\|'M'`, `loading?`, `disabled?`, `fullWidth?=true`, `icon?` (Ionicons name) |
| `TextField` | `label?`, `required?`, `value`, `onChangeText`, `placeholder?`, `helper?`, `error?`, `disabled?`, `secure?` (eye toggle), `multiline?`, `keyboardType?`, `right?`, `picker?` + `onPress` (pressable field with chevron, open a BottomSheet), `autoCapitalize`, `autoComplete`, `textContentType`, `returnKeyType`, `maxLength`, `onSubmitEditing`, `onBlur` |
| `Card` / `InfoCard` / `Divider` | Card: `children`, `onPress?`, `flush?`, `disabled?`. InfoCard: `title`, `body?`, `children?`, `onPress?` |
| `StatusBadge` | `label`, `tone: warning\|neutral\|info\|transit\|success\|danger`, `compact?`, `noDot?` |
| `Chip` | `label`, `selected?`, `onPress`, `disabled?` |
| `Ribbon` | `label`, `tone?: accent\|danger\|success` |
| `ProductCard` | `name`, `price`, `imageUrl?`, `rating?`, `soldCount?`, `ribbon?: {label,tone?}`, `width?`, `onPress` |
| `QuantityStepper` | `value`, `onChange`, `min?=1`, `max?=99`, `disabled?` |
| `BottomSheet` | `visible`, `onClose`, `title?`, `children`, `scrollable?=true` |
| `ConfirmDialog` | `visible`, `title`, `message?`, `confirmLabel?`, `cancelLabel?`, `destructive?`, `loading?`, `onConfirm`, `onCancel` |
| `useToast()` | `show({ type?: 'info'\|'success'\|'error', message, duration? })` (provider is in the root layout) |
| `Skeleton` | `width?`, `height?`, `radius?` |
| `EmptyState` | `heading`, `title?`, `body?`, `primaryLabel?`, `onPrimary?`, `secondaryLabel?`, `onSecondary?` |
| `ErrorState` | `error?` (any thrown value), `heading?`, `title?`, `body?`, `onRetry?`, `retryLabel?`, `secondaryLabel?`, `onSecondary?` |
| `AppText` | `variant?: display\|h2\|h3\|bodyLg\|body\|label\|button\|caption\|wordmark`, `color?: <key of colors>`, plus Text props |
| `LogoMark` | `size?=28` |

Theme (`@/theme`): `colors`, `fonts`, `type`, `space`, `radius`, `shadow`, `layout`,
`toneColors(tone)`, type `Tone`.

## Helpers

- `@/lib/format`: `formatVnd(135000)` = '135.000đ', `formatPhone`, `normalizePhone`, `formatDate`,
  `formatTime`, `formatDateTime` ('28/09/2026 · 14:45'), `formatRelative`, `formatCountdown(sec)`.
- `@/lib/uuid` `uuid()`; `@/lib/jwt` `decodeJwt(token)`; `@/lib/notifications`
  `registerDeviceToken()` (already called after login) and `unregisterDeviceToken()`.
- `@/lib/config` `API_BASE_URL`; `@/lib/queryClient` `queryClient` (4xx are not retried).

## API client (`@/api/client`)

- `api` (axios, baseURL = gateway): sends `Authorization: Bearer`, `X-Client-Type: mobile`
  (native), single-flight refresh and one replay on 401. Per-request `skipAuth: true` disables auth
  and refresh.
- `getApiError(err)` -> `{ code, status, message (Vietnamese), fieldErrors: {field: msg}, retryable }`.
  Errors thrown by `api` are axios errors: always pass them through `getApiError` for display.
- `newIdempotencyKey()` -> value for the `Idempotency-Key` header on POST/PUT writes; one key per
  user action, reused only for retries of that action.
- Success bodies are not wrapped: `(await api.get(url)).data` is the DTO.

## Auth (`@/auth/AuthProvider`)

`useAuth()` -> `{ status: 'loading'|'guest'|'authenticated', user, isShipper, isPending,
login(email, pw), registerAccount({email, username, password}), requestOtp(email),
verifyOtp(email, otp), logout(), refresh() }`, `user = {userId, email, username, roles, status,
emailVerified} | null`.

- Register flow: `registerAccount` -> `requestOtp` -> `verifyOtp` (this signs in). `registerAccount`
  alone does not sign in. Forgot/reset and device sessions: `@/api/auth` (`forgotPassword`,
  `resetPassword`, `listSessions`, `revokeSession`, `revokeOtherSessions`).
- Refresh token = "Option B" (api-auth-user.md section 3): native stores `refreshToken` from the
  login/otp/refresh body in SecureStore and replays it as `X-Refresh-Token`. This needs the
  auth-service change; until it is deployed native refresh/restore cannot work (login itself does).
  Web preview falls back to the HttpOnly cookie (`withCredentials`) and needs the web origin in
  the gateway/auth `CORS_ALLOWED_ORIGINS`.

## Gotchas

- **Expo Router 58 `Tabs` only render screens declared in the layout** (undeclared files are
  hidden and redirected away). That breaks "every file under (buyer)/ is a screen", so `(buyer)`
  and `(shipper)` use a `Stack` (auto-discovers every file, gives real push/back stacks, e.g.
  `product/1` -> `product/2`) and render `BottomTabBar` as a sibling under it, so the bottom nav
  shows on every screen. A tab press is `router.navigate(href)`. Verified on web with nested
  `(buyer)/orders/[id]` and `(buyer)/product/[id]`.
- `/` is both `src/app/index.tsx` and the two `index` screens. It works because the root index only
  redirects; always link to `'/(buyer)'`, never `'/'`.
- Toasts are positioned above the tab bar, also on auth screens (a bit high there).
- Typed routes: `.expo/types` is generated by `expo start`/`expo export`; run one on a fresh clone
  before `tsc`. Redirecting to a route that does not exist yet needs `as Href`.
- Lint (react-hooks/refs): create `Animated.Value` with `useState(() => new Animated.Value(0))[0]`,
  not `useRef(...).current`.
- Android emulator reaches the host at 10.0.2.2 (in config.ts); set `EXPO_PUBLIC_API_BASE_URL` for
  a real phone / LAN IP.
- Push: the backend stores FCM tokens, so we send `getDevicePushTokenAsync()` (not an Expo push
  token) with platform ANDROID/IOS. No-op on web and Expo Go Android.
- Metro in `CI=1` mode disables reload; do not start the dev server with CI set.
