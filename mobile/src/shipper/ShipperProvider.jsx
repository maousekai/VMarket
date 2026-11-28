import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { AppState } from 'react-native'
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake'
import { getApiError } from '../api/client'
import { isTerminalStatus, listMyAssignments } from '../api/deliveryApi'
import { stopTracking } from './locationTracking'
import useShipperTracking from './useShipperTracking'

const ShipperContext = createContext(null)
const KEEP_AWAKE_TAG = 'vmarket-shipper-trip'
/** Dang co chuyen can GPS ma server khong nhan diem nao trong khoang nay -> canh bao. */
export const GPS_STALL_MS = 90_000

/**
 * Du lieu chung cua phan he shipper: chuyen dang xu ly (scope=active), dong bo GPS va giu man hinh
 * sang khi dang giao. Dat o layout (shipper) nen tab Don giao, Vi tri, Lich su va man chi tiet
 * cung thay mot trang thai; GPS van chay khi chuyen giua cac man.
 */
export function ShipperProvider({ children }) {
  const [state, setState] = useState({ status: 'loading', items: [], error: '', revision: 0 })
  const request = useRef(0)

  const load = useCallback(async () => {
    const current = ++request.current
    try {
      const items = await listMyAssignments({ scope: 'active' })
      if (current !== request.current) return
      setState((previous) => ({
        status: 'ready',
        items,
        error: '',
        revision: previous.revision + 1,
      }))
    } catch (failure) {
      if (current !== request.current) return
      const error = getApiError(failure)
      if (error.status === 403) await stopTracking().catch(() => {})
      if (current !== request.current) return
      setState((previous) => ({
        ...previous,
        items: error.status === 403 ? [] : previous.items,
        status: 'error',
        error: error.message,
        revision: error.status === 403 ? 0 : previous.revision,
      }))
    }
  }, [])

  useEffect(() => {
    load()
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active') load()
    })
    return () => {
      subscription.remove()
      request.current += 1
    }
  }, [load])

  const tracking = useShipperTracking(state.items, state.revision, load)
  const stalled = useGpsStall(tracking)

  // Giu man hinh sang khi dang co chuyen can GPS: nhieu ROM (Flyme, MIUI, ColorOS...) chan tac vu
  // nen khi tat man hinh (review PR 68 lan 4, muc 1.2).
  const moving = tracking.trackingCount > 0 && ['foreground', 'background'].includes(tracking.mode)
  useEffect(() => {
    if (!moving) return undefined
    activateKeepAwakeAsync(KEEP_AWAKE_TAG).catch(() => {})
    return () => {
      deactivateKeepAwake(KEEP_AWAKE_TAG).catch(() => {})
    }
  }, [moving])

  const onChanged = useCallback((updated) => {
    request.current += 1
    setState((previous) => ({
      ...previous,
      items: isTerminalStatus(updated.status)
        ? previous.items.filter((item) => item.id !== updated.id)
        : previous.items.map((item) => (item.id === updated.id ? updated : item)),
    }))
  }, [])

  const value = { ...state, load, onChanged, tracking: { ...tracking, stalled }, moving }
  return <ShipperContext.Provider value={value}>{children}</ShipperContext.Provider>
}

/** true khi dang can GPS ma qua GPS_STALL_MS chua co diem nao duoc server nhan. */
function useGpsStall(tracking) {
  const active = tracking.trackingCount > 0 && ['foreground', 'background'].includes(tracking.mode)
  const [since, setSince] = useState(null)
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    if (!active) {
      setSince(null)
      return undefined
    }
    setSince((value) => value ?? Date.now())
    const timer = setInterval(() => setNow(Date.now()), 15_000)
    return () => clearInterval(timer)
  }, [active])
  if (!active || since === null) return false
  const last = Math.max(tracking.lastSentAt || 0, since)
  return now - last > GPS_STALL_MS
}

export function useShipper() {
  const context = useContext(ShipperContext)
  if (!context) throw new Error('useShipper must be used inside ShipperProvider')
  return context
}
