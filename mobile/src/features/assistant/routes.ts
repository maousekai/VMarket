import type { Href } from 'expo-router'

/** Builds a typed-route-safe href with query params (all values are strings). */
export function assistantHref(path: string, params: Record<string, string> = {}): Href {
  const query = Object.entries(params)
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&')
  return `/(buyer)/assistant${path}${query ? `?${query}` : ''}` as Href
}
