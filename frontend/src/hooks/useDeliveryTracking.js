import { useEffect, useRef, useState } from 'react'
import { getApiError } from '../services/api/apiClient'
import { issueTrackingTicket, isTerminalStatus } from '../services/api/deliveryApi'

const RECONNECT_DELAYS_MS = [1000, 2000, 5000, 10000, 15000]

/** URL WebSocket tu server; neu la duong dan tuong doi thi ghep voi origin dang mo. */
export function resolveSocketUrl(data) {
  const raw = data?.webSocketUrl
  if (raw && /^wss?:\/\//.test(raw)) return raw
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const path = raw || `/api/delivery/ws?ticket=${encodeURIComponent(data?.ticket || '')}`
  return `${scheme}//${window.location.host}${path.startsWith('/') ? '' : '/'}${path}`
}

/**
 * Theo doi chuyen giao cua mot don theo thoi gian thuc (PBL6-33).
 *
 * Moi lan ket noi xin mot ve dung-mot-lan (song 30s) roi mo WebSocket chi-nhan cua
 * delivery-service; server day {assignmentId, sequence, status, active, location} ngay khi
 * shipper gui GPS hoac doi trang thai. Mat ket noi thi xin ve moi va noi lai (backoff); chuyen
 * da ket thuc (DELIVERED/RETURNED) hoac khong co quyen theo doi thi dung han.
 *
 * @returns {{connection: 'idle'|'connecting'|'live'|'reconnecting'|'ended'|'error',
 *   update: object|null, error: string, receivedAt: number|null}}
 */
export default function useDeliveryTracking(orderId, { enabled = true } = {}) {
  const [state, setState] = useState({
    connection: 'idle',
    update: null,
    error: '',
    receivedAt: null,
  })
  const sequenceRef = useRef(-1)

  useEffect(() => {
    if (!orderId || !enabled) return undefined
    let disposed = false
    let socket = null
    let timer = null
    let attempt = 0
    let finished = false
    sequenceRef.current = -1

    const scheduleReconnect = () => {
      if (disposed || finished) return
      const delay = RECONNECT_DELAYS_MS[Math.min(attempt, RECONNECT_DELAYS_MS.length - 1)]
      attempt += 1
      setState((previous) => ({ ...previous, connection: 'reconnecting' }))
      timer = setTimeout(connect, delay)
    }

    async function connect() {
      if (disposed) return
      setState((previous) => ({
        ...previous,
        connection: previous.update ? 'reconnecting' : 'connecting',
      }))
      let data
      try {
        data = await issueTrackingTicket(orderId)
      } catch (failure) {
        if (disposed) return
        const status = failure?.response?.status
        if (status === 403 || status === 404) {
          finished = true
          setState((previous) => ({
            ...previous,
            connection: 'error',
            error: getApiError(failure).message,
          }))
          return
        }
        scheduleReconnect()
        return
      }
      if (disposed) return
      try {
        socket = new WebSocket(resolveSocketUrl(data))
      } catch {
        scheduleReconnect()
        return
      }
      socket.onopen = () => {
        attempt = 0
        setState((previous) => ({ ...previous, connection: 'live', error: '' }))
      }
      socket.onmessage = (event) => {
        let message
        try {
          message = JSON.parse(event.data)
        } catch {
          return
        }
        if (message?.type === 'TrackingAccessRevoked') return
        const sequence = Number(message?.sequence ?? message?.version ?? 0)
        if (sequence <= sequenceRef.current) return
        sequenceRef.current = sequence
        if (isTerminalStatus(message.status)) finished = true
        setState((previous) => ({
          ...previous,
          update: message,
          receivedAt: Date.now(),
          connection: finished ? 'ended' : 'live',
        }))
      }
      socket.onclose = () => {
        socket = null
        if (disposed) return
        if (finished) {
          setState((previous) => ({ ...previous, connection: 'ended' }))
          return
        }
        scheduleReconnect()
      }
    }

    connect()
    return () => {
      disposed = true
      clearTimeout(timer)
      if (socket) {
        socket.onclose = null
        socket.close()
      }
    }
  }, [orderId, enabled])

  return state
}
