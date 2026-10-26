import { useEffect, useState } from 'react'

export default function useCountdown(initialSeconds = 0) {
  const [until, setUntil] = useState(() => Date.now() + initialSeconds * 1000)
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  function start(seconds = 60) {
    const time = Date.now()
    setNow(time)
    setUntil(time + seconds * 1000)
  }
  return { remaining: Math.max(0, Math.ceil((until - now) / 1000)), start }
}
