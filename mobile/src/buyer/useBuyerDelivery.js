import { useCallback, useRef, useState } from 'react'
import { useFocusEffect } from 'expo-router'
import { AppState } from 'react-native'
import { getApiError } from '../api/client'
import { getDelivery, issueTrackingTicket } from '../api/deliveryApi'
import { DELIVERY_WS_ORIGIN } from '../config'
import { canBuyerTrack } from '../deliveryStatus'

const BACKOFF = [1000, 2000, 5000, 10_000, 15_000]
const SETTLED = new Set(['DELIVERED', 'RETURNED'])
const INITIAL = { assignment: null, history: [], loading: true, error: '', live: false }

export function resolveTrackingUrl({ webSocketUrl, ticket }, origin = DELIVERY_WS_ORIGIN) {
  const socketOrigin = /^(https?|wss?):\/\/[^/]+/i.exec(origin)?.[0]
  const secure = /^(https|wss):/i.test(socketOrigin || '')
  if (/^wss:\/\//i.test(webSocketUrl)) return webSocketUrl
  if (!secure && /^ws:\/\//i.test(webSocketUrl)) return webSocketUrl
  if (!socketOrigin || (webSocketUrl && !/^\/(?!\/)/.test(webSocketUrl))) {
    throw new Error('Địa chỉ theo dõi giao hàng không hợp lệ.')
  }
  if (!webSocketUrl && !ticket) throw new Error('Thiếu vé theo dõi giao hàng.')
  const path = webSocketUrl || `/api/delivery/ws?ticket=${encodeURIComponent(ticket)}`
  return socketOrigin.replace(/^https?/i, secure ? 'wss' : 'ws') + path
}

export default function useBuyerDelivery(orderId) {
  const [state, setState] = useState(INITIAL)
  const reload = useRef(null)
  useFocusEffect(
    useCallback(() => {
      if (!orderId) return undefined
      setState(INITIAL)
      let foreground = AppState.currentState !== 'background'
      let controller
      let socket
      let timer
      let sequence = -1
      let attempt = 0

      function close() {
        controller?.abort()
        clearTimeout(timer)
        if (socket) {
          socket.onopen = null
          socket.onmessage = null
          socket.onerror = null
          socket.onclose = null
          socket.close()
          socket = null
        }
      }

      function hideLocation() {
        setState((previous) => ({
          ...previous,
          assignment: previous.assignment && { ...previous.assignment, location: null },
          live: false,
          authorized: false,
        }))
      }

      function retryAfter(delay) {
        clearTimeout(timer)
        if (foreground) timer = setTimeout(connect, delay)
      }

      function reconnect() {
        const delay = BACKOFF[Math.min(attempt, BACKOFF.length - 1)]
        attempt += 1
        retryAfter(delay)
      }

      async function connect() {
        close()
        if (!foreground) return
        const request = new AbortController()
        controller = request
        const { signal } = request
        try {
          const data = await getDelivery(orderId, signal)
          if (signal.aborted) return
          const assignment = data.assignment
          let lastStatus = assignment?.status
          setState({
            assignment,
            history: data.history || [],
            loading: false,
            error: '',
            live: false,
            authorized: canBuyerTrack(assignment?.status),
          })
          if (!canBuyerTrack(assignment?.status)) {
            if (!SETTLED.has(assignment?.status)) retryAfter(15_000)
            return
          }
          const ticket = await issueTrackingTicket(orderId, signal)
          if (signal.aborted) return
          const current = new WebSocket(resolveTrackingUrl(ticket))
          socket = current
          sequence = -1
          current.onopen = () => {
            if (signal.aborted) return
            attempt = 0
            setState((previous) => ({ ...previous, live: true }))
          }
          current.onmessage = (event) => {
            if (signal.aborted) return
            let update
            try {
              update = JSON.parse(event.data)
            } catch {
              return
            }
            if (update?.type === 'TrackingAccessRevoked') {
              hideLocation()
              close()
              retryAfter(1000)
              return
            }
            if (
              update?.assignmentId !== assignment?.id ||
              !Number.isFinite(update.sequence) ||
              update.sequence <= sequence
            )
              return
            sequence = update.sequence
            if (!canBuyerTrack(update.status)) {
              hideLocation()
              close()
              retryAfter(1000)
              return
            }
            setState((previous) => ({
              ...previous,
              assignment: {
                ...previous.assignment,
                status: update.status,
                location: update.location,
              },
            }))
            if (update.status !== lastStatus) {
              lastStatus = update.status
              getDelivery(orderId, signal)
                .then((detail) => {
                  if (!signal.aborted)
                    setState((previous) => ({ ...previous, history: detail.history || [] }))
                })
                .catch(() => {})
            }
          }
          current.onclose = () => {
            if (signal.aborted) return
            socket = null
            hideLocation()
            reconnect()
          }
        } catch (failure) {
          if (signal.aborted) return
          const error = getApiError(failure)
          hideLocation()
          if (error.code === 'DELIVERY_NOT_FOUND') {
            setState({ ...INITIAL, loading: false })
            retryAfter(15_000)
            return
          }
          setState((previous) => ({
            ...previous,
            ...(error.status === 403 || error.status === 404
              ? { assignment: null, history: [] }
              : {}),
            loading: false,
            error: error.code === 'TRACKING_NOT_AVAILABLE' ? '' : error.message,
          }))
          if (error.status !== 403 && error.status !== 404) reconnect()
        }
      }

      const listener = AppState.addEventListener('change', (next) => {
        const active = next === 'active'
        if (active === foreground) return
        foreground = active
        if (foreground) connect()
        else {
          close()
          hideLocation()
        }
      })
      connect()
      reload.current = connect
      return () => {
        reload.current = null
        close()
        listener.remove()
        hideLocation()
      }
    }, [orderId]),
  )
  return { ...state, retry: () => reload.current?.() }
}
