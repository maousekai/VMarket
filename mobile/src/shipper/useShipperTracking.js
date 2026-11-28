import { useEffect, useState } from 'react'
import * as Location from 'expo-location'
import { GPS_INTERVAL_MS, isTrackingStatus } from '../api/deliveryApi'
import {
  canShareInBackground,
  pushLocation,
  readLastSentAt,
  requestBackgroundSharing,
  startTracking,
  trackingAccess,
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
export default function useShipperTracking(assignments, revision, reload) {
  const ids = assignments
    .filter((item) => isTrackingStatus(item.status))
    .map((item) => item.id)
    .sort()
  const key = ids.join(',')
  const [state, setState] = useState({ mode: 'off', lastSentAt: null, error: '', count: 0 })
  const [backgroundAvailable, setBackgroundAvailable] = useState(false)
  const [backgroundDenied, setBackgroundDenied] = useState(false)

  useEffect(() => {
    let active = true
    canShareInBackground().then((available) => {
      if (active) setBackgroundAvailable(available)
    })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    if (!revision) return undefined
    let active = true
    let watcher = null
    let poller = null
    const current = key ? key.split(',') : []

    if (!current.length) {
      startTracking([]).catch(() => {})
      setState({ mode: 'off', lastSentAt: null, error: '', count: 0 })
      return undefined
    }

    const refreshLastSent = async (initial = false) => {
      const lastSentAt = await readLastSentAt().catch(() => null)
      const access = await trackingAccess(current).catch(() => null)
      if (!active) return
      if (access?.allRevoked) stopWatching()
      setState((previous) => ({
        ...previous,
        mode: access?.allRevoked ? 'revoked' : previous.mode,
        error: access?.error || previous.error,
        lastSentAt:
          access?.allRevoked || initial
            ? lastSentAt
            : Math.max(lastSentAt, previous.lastSentAt) || null,
        count: access?.available.length ?? previous.count,
      }))
    }

    const stopWatching = () => {
      watcher?.remove()
      watcher = null
    }

    ;(async () => {
      const mode = await startTracking(current).catch(() => 'error')
      if (!active) return
      setState((previous) => ({ ...previous, mode, error: '', count: current.length }))
      await refreshLastSent(true)
      if (!active) return
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
                lastSentAt: null,
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
  }, [key, revision])

  const sharing = Boolean(state.lastSentAt && Date.now() - state.lastSentAt < FRESH_MS)
  return {
    ...state,
    sharing,
    trackingCount: state.mode === 'revoked' ? ids.length : state.count,
    backgroundAvailable,
    backgroundDenied,
    retry: reload,
    enableBackground: async () => {
      const granted = await requestBackgroundSharing()
      setBackgroundDenied(!granted)
      if (granted) await reload()
    },
  }
}
