import Constants, { ExecutionEnvironment } from 'expo-constants'
import * as Notifications from 'expo-notifications'
import { Platform } from 'react-native'

import { api } from '@/api/client'
import { AuthStore } from '@/auth/session'
import { secureStorage } from '@/lib/storage'

const TOKEN_KEY = 'vmarket.pushToken'

/** Token currently bound to the signed-in account (mirrored in SecureStore for restarts). */
let registeredToken: string | null = null

function pushSupported() {
  if (Platform.OS === 'web') return false
  // Remote push was removed from Expo Go on Android (SDK 53+).
  if (
    Platform.OS === 'android' &&
    Constants.executionEnvironment === ExecutionEnvironment.StoreClient
  ) {
    return false
  }
  return true
}

/**
 * Ask permission, fetch the native device token (FCM on Android, APNs on iOS) and register it with
 * notification-service (POST /api/notifications/devices {token, platform}). Safe no-op on web and
 * Expo Go Android. Never throws. Returns true when a token was registered. Idempotent: called on
 * every transition to an authenticated ACTIVE session (login and restore).
 */
export async function registerDeviceToken(): Promise<boolean> {
  if (!pushSupported()) return false
  const generation = AuthStore.getGeneration()
  try {
    const current = await Notifications.getPermissionsAsync()
    const perm = current.granted ? current : await Notifications.requestPermissionsAsync()
    if (!perm.granted) return false
    const device = await Notifications.getDevicePushTokenAsync()
    const token = typeof device.data === 'string' ? device.data : null
    if (!token) return false
    // The account changed while we were asking: never bind this device to the wrong user.
    if (AuthStore.getGeneration() !== generation || !AuthStore.getAccessToken()) return false
    await api.post('/api/notifications/devices', {
      token,
      platform: Platform.OS === 'ios' ? 'IOS' : 'ANDROID',
    })
    registeredToken = token
    await secureStorage.setItem(TOKEN_KEY, token).catch(() => {})
    return true
  } catch {
    return false
  }
}

/**
 * Unbind this device from the current account. Best effort; never throws. Without `accessToken`
 * the normal authenticated client is used (logout). `AuthStore.clear()` passes the access token it
 * is about to drop, because the request must not depend on the already-cleared store.
 */
export async function unregisterDeviceToken(accessToken?: string | null): Promise<void> {
  if (!pushSupported()) return
  try {
    const token = registeredToken ?? (await secureStorage.getItem(TOKEN_KEY))
    if (!token) return
    registeredToken = null
    await secureStorage.removeItem(TOKEN_KEY).catch(() => {})
    await api.delete('/api/notifications/devices', {
      data: { token },
      ...(accessToken
        ? { skipAuth: true, headers: { Authorization: `Bearer ${accessToken}` } }
        : {}),
    })
  } catch {
    // ignore
  }
}

// Every way of ending a session (logout, change-password, suspension, expired refresh token)
// goes through AuthStore.clear(), so unbinding the device there covers all of them.
AuthStore.addBeforeClear((accessToken) => {
  void unregisterDeviceToken(accessToken)
})
