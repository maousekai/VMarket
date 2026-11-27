import { useCallback, useEffect, useRef, useState } from 'react'
import { getApiError } from '../services/api/apiClient'
import {
  GPS_MIN_INTERVAL_MS,
  GPS_SEND_INTERVAL_MS,
  sendLocation,
} from '../services/api/deliveryApi'

export const GPS_PERMISSION_LABELS = Object.freeze({
  unsupported: 'Thiết bị không hỗ trợ định vị',
  prompt: 'Chưa cấp quyền vị trí',
  granted: 'Đã cấp quyền vị trí',
  denied: 'Quyền vị trí bị chặn — hãy bật lại trong cài đặt trình duyệt',
  unknown: 'Chưa rõ quyền vị trí',
})

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
 * Chia se GPS cua shipper cho mot chuyen dang chay (PBL6-33).
 *
 * watchPosition giu vi tri moi nhat; moi GPS_SEND_INTERVAL_MS (12s, tren gioi han 10s cua server) gui mot
 * diem neu co vi tri moi. Server day diem do toi nguoi mua qua WebSocket ngay lap tuc nen do tre
 * hien thi = do tre mang, duoi 5 giay. Wake Lock giu man hinh sang de trinh duyet di dong khong
 * dung GPS khi dang giao.
 */
export default function useShipperGps(assignmentId, { enabled, onStopped } = {}) {
  const [state, setState] = useState({
    sharing: false,
    lastSentAt: null,
    error: '',
    warning: '',
    revoked: false,
  })
  const [permission, setPermission] = useGeolocationPermission()
  const latest = useRef(null)
  const sentStamp = useRef(null)
  const lastAttemptAt = useRef(0)
  const inFlight = useRef(false)
  const stoppedRef = useRef(onStopped)
  useEffect(() => {
    stoppedRef.current = onStopped
  }, [onStopped])

  // Doi chuyen thi bo trang thai "mat quyen" cua chuyen cu.
  useEffect(() => {
    setState((previous) =>
      previous.revoked ? { ...previous, revoked: false, error: '' } : previous,
    )
  }, [assignmentId])

  const push = useCallback(async () => {
    const position = latest.current
    if (!assignmentId || !position || inFlight.current) return
    if (position.timestamp === sentStamp.current) return
    // Khong bao gio gui nhanh hon gioi han cua server, ke ca khi lan truoc that bai.
    if (Date.now() - lastAttemptAt.current < GPS_MIN_INTERVAL_MS) return
    lastAttemptAt.current = Date.now()
    inFlight.current = true
    try {
      await sendLocation(assignmentId, position)
      // Chi danh dau "da gui" khi server nhan; 429 thi diem nay duoc gui lai o luot sau.
      sentStamp.current = position.timestamp
      setState((previous) => ({ ...previous, lastSentAt: Date.now(), error: '', warning: '' }))
    } catch (failure) {
      const error = getApiError(failure)
      const status = failure?.response?.status
      if (status === 429) {
        setState((previous) => ({
          ...previous,
          warning: 'Máy chủ yêu cầu gửi chậm lại, vị trí sẽ được gửi lại ở lượt sau.',
        }))
      } else if (error.code === 'TRACKING_STOPPED') {
        stoppedRef.current?.()
      } else if (status === 403) {
        // Shipper bi doi hoac bi khoa: dung han viec lay vi tri cho chuyen nay.
        setState((previous) => ({
          ...previous,
          sharing: false,
          revoked: true,
          error: 'Bạn không còn quyền gửi vị trí cho chuyến này.',
        }))
        stoppedRef.current?.()
      } else {
        setState((previous) => ({ ...previous, error: error.message }))
      }
    } finally {
      inFlight.current = false
    }
  }, [assignmentId])

  const active = Boolean(enabled && assignmentId && !state.revoked)

  useEffect(() => {
    const geo = geolocation()
    if (!active || !geo) {
      setState((previous) => (previous.sharing ? { ...previous, sharing: false } : previous))
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

    const watchId = geo.watchPosition(
      (position) => {
        latest.current = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          timestamp: position.timestamp,
        }
        setPermission('granted')
        setState((previous) =>
          previous.sharing && !previous.error
            ? previous
            : { ...previous, sharing: true, error: '' },
        )
        if (lastAttemptAt.current === 0) push()
      },
      (failure) => {
        if (failure.code === 1) setPermission('denied')
        setState((previous) => ({
          ...previous,
          sharing: false,
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

  return { ...state, permission }
}
