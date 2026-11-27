import { useEffect, useState } from 'react'
import { AppState } from 'react-native'
import * as Location from 'expo-location'
import { GPS_INTERVAL_MS, isTrackingStatus } from '../api/deliveryApi'
import {
  pushLocation,
  readLastSentAt,
  requestBackgroundSharing,
  startTracking,
  stopTracking,
} from './locationTracking'

// Chi bao "dang chia se" khi server da nhan mot diem gan day (khong tin rieng viec da xin quyen).
const FRESH_MS = 60_000

/**
 * Dong bo viec gui GPS voi danh sach chuyen cua shipper: co chuyen dang chay thi bat, het thi tat.
 * Che do 'foreground' (khong co quyen nen) thi tu theo doi vi tri khi man hinh dang mo.
 * Thu lai moi khi app quay ve foreground (vd shipper vua bat lai dinh vi trong Cai dat).
 *
 * mode: off | denied | servicesOff | foreground | background | revoked | error
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
    const subscription = AppState.addEventListener?.('change', (next) => {
      if (next === 'active') setAttempt((value) => value + 1)
    })
    return () => subscription?.remove?.()
  }, [])

  useEffect(() => {
    let active = true
    let watcher = null
    let poller = null
    const current = key ? key.split(',') : []

    if (!current.length) {
      stopTracking().catch(() => {})
      setState({ mode: 'off', lastSentAt: null, error: '' })
      return undefined
    }

    const refreshLastSent = async () => {
      const lastSentAt = await readLastSentAt().catch(() => null)
      if (active) setState((previous) => ({ ...previous, lastSentAt }))
    }

    const stopWatching = () => {
      watcher?.remove()
      watcher = null
    }

    ;(async () => {
      const mode = await startTracking(current).catch(() => 'error')
      if (!active) return
      setState((previous) => ({ ...previous, mode, error: '' }))
      refreshLastSent()
      poller = setInterval(refreshLastSent, 5000)
      if (mode !== 'foreground') return
      try {
        watcher = await Location.watchPositionAsync(
          { accuracy: Location.Accuracy.High, timeInterval: GPS_INTERVAL_MS, distanceInterval: 0 },
          async (position) => {
            const result = await pushLocation(position.coords)
            if (!active) return
            if (result.stopped) {
              // Moi chuyen bi tu choi (bi khoa / doi shipper / ket thuc): dung lay vi tri ngay.
              stopWatching()
              setState((previous) => ({
                ...previous,
                mode: result.error ? 'revoked' : 'off',
                error: result.error || '',
              }))
              return
            }
            setState((previous) => ({
              ...previous,
              lastSentAt: result.sent ? Date.now() : previous.lastSentAt,
              error: result.error || '',
            }))
          },
          (message) => {
            if (active)
              setState((previous) => ({
                ...previous,
                mode: 'error',
                error: String(message || 'Không lấy được vị trí'),
              }))
          },
        )
        if (!active) stopWatching()
      } catch (failure) {
        if (active)
          setState((previous) => ({
            ...previous,
            mode: 'error',
            error: failure?.message || 'Không lấy được vị trí',
          }))
      }
    })()

    return () => {
      active = false
      stopWatching()
      clearInterval(poller)
      // Che do nen cu chay khi roi man hinh; chi dung khi khong con chuyen nao (nhanh tren).
    }
  }, [key, attempt])

  const sharing = Boolean(state.lastSentAt && Date.now() - state.lastSentAt < FRESH_MS)
  return {
    ...state,
    sharing,
    trackingCount: ids.length,
    retry: () => setAttempt((value) => value + 1),
    enableBackground: async () => {
      await requestBackgroundSharing()
      setAttempt((value) => value + 1)
    },
  }
}
