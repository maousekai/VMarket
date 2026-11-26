import { useEffect, useState } from 'react'
import * as Location from 'expo-location'
import { GPS_INTERVAL_MS, isTrackingStatus } from '../api/deliveryApi'
import { pushLocation, startTracking, stopTracking } from './locationTracking'

/**
 * Dong bo viec gui GPS voi danh sach chuyen cua shipper: co chuyen dang chay thi bat, het thi tat.
 * Che do 'foreground' (khong co quyen nen) thi tu theo doi vi tri khi man hinh dang mo.
 */
export default function useShipperTracking(assignments) {
  const ids = assignments
    .filter((item) => isTrackingStatus(item.status))
    .map((item) => item.id)
    .sort()
  const key = ids.join(',')
  const [state, setState] = useState({ mode: 'off', lastSentAt: null, error: '' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    let subscription = null
    const current = key ? key.split(',') : []

    if (!current.length) {
      stopTracking().catch(() => {})
      setState({ mode: 'off', lastSentAt: null, error: '' })
      return undefined
    }

    ;(async () => {
      const mode = await startTracking(current).catch(() => 'denied')
      if (!active) return
      setState((previous) => ({ ...previous, mode, error: '' }))
      if (mode !== 'foreground') return
      subscription = await Location.watchPositionAsync(
        { accuracy: Location.Accuracy.High, timeInterval: GPS_INTERVAL_MS, distanceInterval: 0 },
        async (position) => {
          const result = await pushLocation(position.coords)
          if (!active) return
          setState((previous) => ({
            ...previous,
            lastSentAt: result.sent ? Date.now() : previous.lastSentAt,
            error: result.error || '',
          }))
        },
      ).catch(() => null)
      if (!active) subscription?.remove()
    })()

    return () => {
      active = false
      subscription?.remove()
      // Che do nen cu chay khi roi man hinh; chi dung khi khong con chuyen nao (nhanh tren).
    }
  }, [key, attempt])

  return { ...state, trackingCount: ids.length, retry: () => setAttempt((value) => value + 1) }
}
