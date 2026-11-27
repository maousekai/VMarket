// Moi request di qua API Gateway (cong 8080). EXPO_PUBLIC_* duoc Expo nhung luc build.
//  - Android emulator: 10.0.2.2 tro ve may host.
//  - May that / iOS: dat EXPO_PUBLIC_API_URL=http://<IP-LAN-cua-may>:8080 trong mobile/.env.local
//  - Ban demo: URL Gateway cong khai.
export const API_URL = (process.env.EXPO_PUBLIC_API_URL || 'http://10.0.2.2:8080').replace(
  /\/+$/,
  '',
)

export const REQUEST_TIMEOUT_MS = 15000

/** Trang web VMarket (luong nguoi mua chua co tren app). Bo trong thi an nut mo web. */
export const WEB_URL = (process.env.EXPO_PUBLIC_WEB_URL || '').replace(/\/+$/, '')
