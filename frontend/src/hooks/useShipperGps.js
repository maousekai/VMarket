import { useCallback, useEffect, useRef, useState } from 'react'
import { getApiError } from '../services/api/apiClient'
import {
  GPS_MIN_INTERVAL_MS,
  GPS_SEND_INTERVAL_MS,
  sendLocations,
} from '../services/api/deliveryApi'

export const GPS_PERMISSION_LABELS = Object.freeze({
  unsupported: 'Thiết bị không hỗ trợ định vị',
  prompt: 'Chưa cấp quyền vị trí',
  granted: 'Đã cấp quyền vị trí',
  denied: 'Quyền vị trí bị chặn — hãy bật lại trong cài đặt trình duyệt',
  unknown: 'Chưa rõ quyền vị trí',
})

/** Chi bao "dang chia se" khi server da nhan mot diem gan day. */
const FRESH_MS = 60_000

const geolocation = () =>
  typeof navigator !== 'undefined' && navigator.geolocation ? navigator.geolocation : null

/** Trang thai quyen vi tri cua trinh duyet, cap nhat khi nguoi dung doi trong cai dat. */
export function useGeolocationPermission() {
  const [permission, setPermission] = useState(() => (geolocation() ? 'unknown' : 'unsupported'))

  useEffect(() => {
    if (!geolocation() || !navigator.permissions?.query) return undefined
    let status
    let current = true
    const sync = () => current && status && setPermission(status.state)
    navigator.permissions
      .query({ name: 'geolocation' })
      .then((result) => {
        status = result
        sync()
        status.addEventListener?.('change', sync)
      })
      .catch(() => {})
    return () => {
      current = false
      status?.removeEventListener?.('change', sync)
    }
  }, [])

  return [permission, setPermission]
}

/**
 * Chia se GPS tu TRINH DUYET cho moi chuyen dang chay (PBL6-33, review PR 68 muc 4.12).
 *
 * Mobile la nguon GPS chinh; web chi gui khi shipper chu dong bat "Chia se tu trinh duyet nay".
 * Moi GPS_SEND_INTERVAL_MS (12s, tren gioi han 10s cua server) gui MOT request cho moi chuyen.
 * Ket qua tung chuyen: GPS_SOURCE_CONFLICT (app di dong dang gui) va PING_TOO_FREQUENT duoc bao
 * ro; 403 (bi khoa / mat quyen) thi dung han. Wake Lock giu man hinh sang va duoc xin lai khi tab
 * hien tro lai.
 */
export default function useShipperGps({ enabled } = {}) {
  const [state, setState] = useState({
    lastSentAt: null,
    error: '',
    warning: '',
    revoked: false,
    watching: false,
  })
  const [permission, setPermission] = useGeolocationPermission()
  const latest = useRef(null)
  const sentStamp = useRef(null)
  const lastAttemptAt = useRef(0)
  const inFlight = useRef(false)

  const push = useCallback(async () => {
    const position = latest.current
    if (!position || inFlight.current || position.timestamp === sentStamp.current) return
    if (Date.now() - lastAttemptAt.current < GPS_MIN_INTERVAL_MS) return
    lastAttemptAt.current = Date.now()
    inFlight.current = true
    try {
      const { results = [] } = (await sendLocations(position)) || {}
      const codes = results.map((row) => row.result)
      if (codes.includes('OK')) {
        sentStamp.current = position.timestamp
        setState((previous) => ({ ...previous, lastSentAt: Date.now(), error: '', warning: '' }))
      } else if (codes.length && codes.every((code) => code === 'GPS_SOURCE_CONFLICT')) {
        setState((previous) => ({
          ...previous,
          warning:
            'Ứng dụng di động đang gửi vị trí cho chuyến này, trình duyệt tạm không gửi để tránh trùng.',
        }))
      } else if (codes.includes('PING_TOO_FREQUENT')) {
        setState((previous) => ({
          ...previous,
          warning: 'Máy chủ yêu cầu gửi chậm lại, vị trí sẽ được gửi lại ở lượt sau.',
        }))
      } else if (!codes.length) {
        setState((previous) => ({ ...previous, warning: 'Không có chuyến nào đang giao.' }))
      }
    } catch (failure) {
      const status = failure?.response?.status
      if (status === 403) {
        setState((previous) => ({
          ...previous,
          revoked: true,
          watching: false,
          error: 'Bạn không còn quyền gửi vị trí (tài khoản bị khoá hoặc mất phân công).',
        }))
      } else if (status === 429) {
        setState((previous) => ({
          ...previous,
          warning: 'Máy chủ yêu cầu gửi chậm lại, vị trí sẽ được gửi lại ở lượt sau.',
        }))
      } else {
        setState((previous) => ({ ...previous, error: getApiError(failure).message }))
      }
    } finally {
      inFlight.current = false
    }
  }, [])

  const active = Boolean(enabled && !state.revoked)

  useEffect(() => {
    const geo = geolocation()
    if (!active || !geo) {
      setState((previous) => (previous.watching ? { ...previous, watching: false } : previous))
      return undefined
    }
    let wakeLock = null
    let disposed = false
    // Trinh duyet tu nha Wake Lock khi tab bi an: xin lai moi khi tab hien tro lai.
    const acquireWakeLock = () => {
      if (disposed || wakeLock || document.visibilityState !== 'visible') return
      navigator.wakeLock
        ?.request?.('screen')
        .then((lock) => {
          if (disposed) {
            lock.release()
            return
          }
          wakeLock = lock
          lock.addEventListener?.('release', () => {
            wakeLock = null
          })
        })
        .catch(() => {})
    }
    acquireWakeLock()
    document.addEventListener('visibilitychange', acquireWakeLock)
    setState((previous) => ({ ...previous, watching: true, error: '' }))

    const watchId = geo.watchPosition(
      (position) => {
        latest.current = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          timestamp: position.timestamp,
        }
        setPermission('granted')
        if (lastAttemptAt.current === 0) push()
      },
      (failure) => {
        if (failure.code === 1) setPermission('denied')
        setState((previous) => ({
          ...previous,
          error:
            failure.code === 1
              ? GPS_PERMISSION_LABELS.denied
              : 'Không lấy được vị trí, đang thử lại…',
        }))
      },
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 },
    )
    const interval = setInterval(push, GPS_SEND_INTERVAL_MS)
    return () => {
      disposed = true
      geo.clearWatch(watchId)
      clearInterval(interval)
      document.removeEventListener('visibilitychange', acquireWakeLock)
      wakeLock?.release?.().catch(() => {})
      latest.current = null
      sentStamp.current = null
      lastAttemptAt.current = 0
    }
  }, [active, push, setPermission])

  const sharing = Boolean(state.lastSentAt && Date.now() - state.lastSentAt < FRESH_MS)
  return { ...state, sharing, permission }
}
