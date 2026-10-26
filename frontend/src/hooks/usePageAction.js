import { useCallback, useEffect, useRef } from 'react'

// Navigation away cancels UI side effects, even when an HTTP request has already reached the server.
export default function usePageAction() {
  const mounted = useRef(false)
  const flight = useRef(null)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      flight.current = null
    }
  }, [])

  return useCallback((auth, allowSessionChange = false) => {
    if (flight.current) return null
    const ticket = {}
    flight.current = ticket
    const identity = auth?.getSnapshot?.().session?.userId || null
    return {
      current: () =>
        mounted.current &&
        flight.current === ticket &&
        (allowSessionChange ||
          !auth?.getSnapshot ||
          (auth.getSnapshot().session?.userId || null) === identity),
      mounted: () => mounted.current && flight.current === ticket,
      finish: () => {
        if (flight.current === ticket) flight.current = null
      },
    }
  }, [])
}
