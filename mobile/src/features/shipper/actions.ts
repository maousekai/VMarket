import { useMutation } from '@tanstack/react-query'
import { router, type Href } from 'expo-router'
import { useCallback } from 'react'

import { getApiError, type ApiError } from '@/api/client'
import { retryDelivery, updateStatus, type StatusUpdate } from '@/api/delivery'
import { useToast } from '@/components/ui'

import { useApplyAssignment, useRefreshDelivery } from './hooks'

export const taskHref = (orderId: string, sub?: string) =>
  `/(shipper)/tasks/${orderId}${sub ? `/${sub}` : ''}` as Href

/** Back to the status-driven detail screen of an order (pops any step screens above it). */
export function goToTask(orderId: string) {
  router.dismissTo(taskHref(orderId))
}

/**
 * Status / retry writes for one order. Responses replace the cached assignment, so the
 * status-driven detail screen switches view by itself. `recover` shows the backend message and,
 * on 409 (stale version / invalid transition), reloads the order so the UI follows the server.
 */
export function useDeliveryMutations(orderId: string) {
  const apply = useApplyAssignment(orderId)
  const refresh = useRefreshDelivery(orderId)
  const toast = useToast()

  const status = useMutation({
    mutationFn: ({ id, body }: { id: string; body: StatusUpdate }) => updateStatus(id, body),
    onSuccess: apply,
  })
  const retry = useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) => retryDelivery(id, version),
    onSuccess: apply,
  })

  const recover = useCallback(
    async (error: unknown): Promise<ApiError> => {
      const err = getApiError(error)
      toast.show({ type: 'error', message: err.message })
      if (err.status === 409) {
        try {
          await refresh()
        } catch {
          // the next pull-to-refresh will catch up
        }
      }
      return err
    },
    [toast, refresh],
  )

  return { status, retry, recover, refresh }
}
