import { useCallback, useEffect, useState } from 'react'

// Tai du lieu mot lan theo `key`; bo qua ket qua cua lan tai cu khi key doi hoac unmount.
// `load` phai on dinh theo `key` (dong goi bang useCallback o noi goi).
export default function useRemoteData(load, key) {
  const [state, setState] = useState({ key, status: 'loading', data: null, error: null })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    setState((current) =>
      current.key === key && current.status === 'loading'
        ? current
        : { key, status: 'loading', data: null, error: null },
    )
    Promise.resolve()
      .then(load)
      .then(
        (data) => !cancelled && setState({ key, status: 'ready', data, error: null }),
        (error) => !cancelled && setState({ key, status: 'error', data: null, error }),
      )
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `key` identifies the request
  }, [key, attempt])

  const reload = useCallback(() => setAttempt((value) => value + 1), [])
  // Until the effect for a new key runs, never show the previous key's data under it.
  if (state.key !== key) return { status: 'loading', data: null, error: null, reload }
  return { status: state.status, data: state.data, error: state.error, reload }
}
