// Web fallback (expo-secure-store has no web support). Only used for the browser
// preview of the app; native builds resolve storage.native.ts instead.
function safeLocalStorage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

export const secureStorage = {
  async getItem(key: string): Promise<string | null> {
    return safeLocalStorage()?.getItem(key) ?? null
  },
  async setItem(key: string, value: string): Promise<void> {
    safeLocalStorage()?.setItem(key, value)
  },
  async removeItem(key: string): Promise<void> {
    safeLocalStorage()?.removeItem(key)
  },
}
