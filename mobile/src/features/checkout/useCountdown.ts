import { useEffect, useState } from 'react'

/** Seconds left until `target` (ISO string), ticking every second; 0 when passed or unknown. */
export function useCountdown(target: string | null | undefined): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!target) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [target])
  return target ? Math.max(0, Math.ceil((new Date(target).getTime() - now) / 1000)) : 0
}
