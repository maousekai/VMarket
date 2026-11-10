import { useEffect, useState } from 'react'
import { AppState, Platform } from 'react-native'

import type { DeliveryAssignment } from '@/api/tracking'

export const POLL_INTERVAL_MS = 15_000
/** A fix older than this while the delivery is active means the shipper's GPS / network is lost. */
export const STALE_AFTER_MS = 60_000

export type TrackingState = 'waiting' | 'live' | 'stale' | 'delivered' | 'returned'

const ACTIVE = ['PICKED_UP', 'IN_TRANSIT', 'RETURNING']

export function hasLocation(a: DeliveryAssignment | undefined | null): boolean {
  return !!a && a.latitude !== null && a.longitude !== null && a.latitude !== undefined
}

export function trackingState(
  a: DeliveryAssignment | undefined | null,
  now: number,
  fetchFailed = false,
): TrackingState {
  if (!a) return 'waiting'
  if (a.status === 'DELIVERED') return 'delivered'
  if (a.status === 'RETURNED') return 'returned'
  if (!ACTIVE.includes(a.status)) return 'waiting'
  const at = a.locationAt ? new Date(a.locationAt).getTime() : NaN
  if (fetchFailed || !hasLocation(a) || Number.isNaN(at) || now - at > STALE_AFTER_MS)
    return 'stale'
  return 'live'
}

/** Final states need no more polling. */
export const isFinalTracking = (s: TrackingState) => s === 'delivered' || s === 'returned'

export const formatCoords = (a: Pick<DeliveryAssignment, 'latitude' | 'longitude'>) =>
  `${Number(a.latitude).toFixed(5)}, ${Number(a.longitude).toFixed(5)}`

/** geo: URI on Android (opens the default maps app), Apple Maps on iOS, Google Maps on web. */
export function mapUrl(lat: number, lng: number): string {
  const ll = `${lat},${lng}`
  return Platform.select({
    android: `geo:${ll}?q=${ll}(Shipper)`,
    ios: `http://maps.apple.com/?ll=${ll}&q=Shipper`,
    default: `https://www.google.com/maps?q=${ll}`,
  })
}

/** Re-renders every `ms` so "stale" can flip without a new response. */
export function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(t)
  }, [ms])
  return now
}

/** false while the app is in the background (polling pauses). */
export function useAppActive(): boolean {
  const [active, setActive] = useState(AppState.currentState === 'active')
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => setActive(s === 'active'))
    return () => sub.remove()
  }, [])
  return active
}
