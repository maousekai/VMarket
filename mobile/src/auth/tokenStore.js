import * as SecureStore from 'expo-secure-store'

// Token chi nam trong Keychain (iOS) / Keystore (Android), khong bao gio o AsyncStorage.
// AFTER_FIRST_UNLOCK de task GPS chay nen van doc duoc token khi man hinh khoa.
const OPTIONS = { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK }
const ACCESS_KEY = 'vmarket.accessToken'
const REFRESH_KEY = 'vmarket.refreshToken'

export async function loadTokens() {
  const [accessToken, refreshToken] = await Promise.all([
    SecureStore.getItemAsync(ACCESS_KEY, OPTIONS),
    SecureStore.getItemAsync(REFRESH_KEY, OPTIONS),
  ])
  return { accessToken, refreshToken }
}

export async function saveTokens({ accessToken, refreshToken }) {
  const writes = []
  if (accessToken) writes.push(SecureStore.setItemAsync(ACCESS_KEY, accessToken, OPTIONS))
  if (refreshToken) writes.push(SecureStore.setItemAsync(REFRESH_KEY, refreshToken, OPTIONS))
  await Promise.all(writes)
}

export async function clearTokens() {
  await Promise.all([
    SecureStore.deleteItemAsync(ACCESS_KEY, OPTIONS),
    SecureStore.deleteItemAsync(REFRESH_KEY, OPTIONS),
  ])
}
