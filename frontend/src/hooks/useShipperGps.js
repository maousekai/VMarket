import { useCallback, useEffect, useRef, useState } from 'react'
import { getApiError } from '../services/api/apiClient'
import { GPS_MIN_INTERVAL_MS, sendLocation } from '../services/api/deliveryApi'

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
 * watchPosition giu vi tri moi nhat; moi GPS_MIN_INTERVAL_MS (gioi han cua server) gui mot
 * diem neu co vi tri moi. Server day diem do toi nguoi mua qua WebSocket ngay lap tuc nen do tre
 * hien thi = do tre mang, duoi 5 giay. Wake Lock giu man hinh sang de trinh duyet di dong khong
 * dung GPS khi dang giao.
 */
export default function useShipperGps(assignmentId, { enabled, onStopped } = {}) {
  const [state, setState] = useState({ sharing: false, lastSentAt: null, error: '' })
  const [permission, setPermission] = useGeolocationPermission()
  const latest = useRef(null)
  const sentStamp = useRef(null)
  const stoppedRef = useRef(onStopped)
  stoppedRef.current = onStopped

  const push = useCallback(async () => {
    const position = latest.current
    if (!assignmentId || !position || position.timestamp === sentStamp.current) return
    sentStamp.current = position.timestamp
    try {
      await sendLocation(assignmentId, position)
      setState((previous) => ({ ...previous, lastSentAt: Date.now(), error: '' }))
    } catch (failure) {
      const error = getApiError(failure)
      if (failure?.response?.status === 429) return
      if (error.code === 'TRACKING_STOPPED') {
        stoppedRef.current?.()
        return
      }
      setState((previous) => ({ ...previous, error: error.message }))
    }
  }, [assignmentId])

  useEffect(() => {
    const geo = geolocation()
    if (!enabled || !assignmentId || !geo) {
      setState((previous) => ({ ...previous, sharing: false }))
      return undefined
    }
    let wakeLock = null
    let disposed = false
    navigator.wakeLock
      ?.request?.('screen')
      .then((lock) => {
        if (disposed) lock.release()
        else wakeLock = lock
      })
      .catch(() => {})

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
        if (sentStamp.current === null) push()
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
    const interval = setInterval(push, GPS_MIN_INTERVAL_MS)
    return () => {
      disposed = true
      geo.clearWatch(watchId)
      clearInterval(interval)
      wakeLock?.release?.().catch(() => {})
      latest.current = null
      sentStamp.current = null
    }
  }, [assignmentId, enabled, push, setPermission])

  return { ...state, permission }
}
