import { useCallback, useEffect, useRef, useState } from 'react'

import { clearPending, loadPending, savePending, type PendingWrite } from './pending'

/**
 * Unconfirmed status write of one kind for one order, persisted so it survives an app restart.
 * `pending` is `undefined` until storage was read, `null` when there is none.
 */
export function usePendingWrite(kind: PendingWrite['kind'], orderId: string) {
  const [pending, setState] = useState<PendingWrite | null | undefined>(undefined)

  // A save/clear made while the initial load is still running wins over the (older) loaded value.
  const touched = useRef(false)

  useEffect(() => {
    let alive = true
    touched.current = false
    void loadPending(kind, orderId).then((p) => {
      if (alive && !touched.current) setState(p)
    })
    return () => {
      alive = false
    }
  }, [kind, orderId])

  const save = useCallback(
    async (write: PendingWrite) => {
      touched.current = true
      setState(write)
      await savePending(orderId, write)
    },
    [orderId],
  )

  const clear = useCallback(async () => {
    touched.current = true
    setState(null)
    await clearPending(kind, orderId)
  }, [kind, orderId])

  return { pending, save, clear }
}
