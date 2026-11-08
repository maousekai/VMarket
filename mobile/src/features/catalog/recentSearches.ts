import AsyncStorage from '@react-native-async-storage/async-storage'

const KEY = 'vmarket.recentSearches'
const MAX = 6

export async function loadRecentSearches(): Promise<string[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY)
    const list: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list.filter((s): s is string => typeof s === 'string') : []
  } catch {
    return []
  }
}

/** Remember a keyword (most recent first, de-duplicated). Storage failures are ignored. */
export async function rememberSearch(keyword: string): Promise<void> {
  const term = keyword.trim()
  if (!term) return
  try {
    const list = (await loadRecentSearches()).filter((s) => s.toLowerCase() !== term.toLowerCase())
    await AsyncStorage.setItem(KEY, JSON.stringify([term, ...list].slice(0, MAX)))
  } catch {
    // recent searches are a convenience only
  }
}

export async function clearRecentSearches(): Promise<void> {
  try {
    await AsyncStorage.removeItem(KEY)
  } catch {
    // ignore
  }
}
