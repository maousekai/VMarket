import { useCallback, useEffect, useState } from 'react'

export const RESEND_COOLDOWN_SECONDS = 60

/** Whole-second countdown. `start(n)` (re)starts it; `remaining` is 0 when idle. */
export function useCountdown(initial = 0) {
  const [remaining, setRemaining] = useState(initial)
  useEffect(() => {
    if (remaining <= 0) return
    const timer = setTimeout(() => setRemaining((r) => r - 1), 1000)
    return () => clearTimeout(timer)
  }, [remaining])
  const start = useCallback((seconds = RESEND_COOLDOWN_SECONDS) => setRemaining(seconds), [])
  return { remaining, start }
}
