import {
  useInfiniteQuery,
  useQueries,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query'
import { useCallback, useEffect, useMemo } from 'react'

import { api } from '@/api/client'
import {
  ASSIGNMENTS_PAGE_SIZE,
  fetchAssignments,
  fetchDeliveryDetail,
  type Assignment,
  type DeliveryDetail,
} from '@/api/delivery'
import { useAuth } from '@/auth/AuthProvider'

import type { OrderItem } from './lib/order'

const LIST_KEY = ['shipper', 'assignments'] as const
const detailKey = (orderId: string) => ['shipper', 'detail', orderId] as const

/**
 * All assignments of the signed-in shipper (newest first), 100 per page. The API has no status
 * filter and an old IN_TRANSIT order can sit behind 100 newer ones, so every page is loaded;
 * `complete` says the list (and therefore the GPS tracker input) is whole.
 */
export function useAssignments() {
  const { isShipper } = useAuth()
  const query = useInfiniteQuery({
    queryKey: LIST_KEY,
    queryFn: ({ pageParam }) => fetchAssignments(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, pages) =>
      last.length < ASSIGNMENTS_PAGE_SIZE ? undefined : pages.length,
    enabled: isShipper,
    refetchInterval: 60_000,
  })
  const { hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage } = query
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage && !isFetchNextPageError) void fetchNextPage()
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage])
  const assignments = useMemo(() => query.data?.pages.flat() ?? [], [query.data])
  return { ...query, assignments, complete: query.isSuccess && !hasNextPage }
}

export function useDelivery(orderId: string) {
  return useQuery({
    queryKey: detailKey(orderId),
    queryFn: () => fetchDeliveryDetail(orderId),
    enabled: !!orderId,
  })
}

/** Writes a fresh Assignment (response of a mutation) into the detail + list caches. */
export function useApplyAssignment(orderId: string) {
  const qc = useQueryClient()
  return useCallback(
    (next: Assignment) => {
      qc.setQueryData<DeliveryDetail>(detailKey(orderId), (old) =>
        old ? { ...old, assignment: next } : old,
      )
      qc.setQueryData<InfiniteData<Assignment[], number>>(LIST_KEY, (old) =>
        old
          ? { ...old, pages: old.pages.map((p) => p.map((a) => (a.id === next.id ? next : a))) }
          : old,
      )
      void qc.invalidateQueries({ queryKey: ['shipper'] })
    },
    [qc, orderId],
  )
}

/** Refetch helper used after 409 conflicts / unknown results. */
export function useRefreshDelivery(orderId: string) {
  const qc = useQueryClient()
  return useCallback(async () => {
    const detail = await qc.fetchQuery({
      queryKey: detailKey(orderId),
      queryFn: () => fetchDeliveryDetail(orderId),
      staleTime: 0,
    })
    void qc.invalidateQueries({ queryKey: LIST_KEY })
    return detail
  }, [qc, orderId])
}

interface ProductPayload {
  product?: { name?: string; variants?: { id: string; attributes?: Record<string, string> }[] }
}

/** `variantId -> "Tên · Thuộc tính"` for the items (product-service is public). Falls back to ''. */
export function useItemLabels(items: OrderItem[]): Record<string, string> {
  const ids = useMemo(() => [...new Set(items.map((i) => i.productId).filter(Boolean))], [items])
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ['shipper', 'product', id],
      queryFn: async () =>
        (await api.get<ProductPayload>(`/api/products/${id}`, { skipAuth: true })).data,
      staleTime: 10 * 60_000,
      retry: false,
    })),
  })
  return useMemo(() => {
    const map: Record<string, string> = {}
    results.forEach((r, idx) => {
      const p = r.data?.product
      if (!p) return
      items
        .filter((i) => i.productId === ids[idx])
        .forEach((i) => {
          const variant = p.variants?.find((v) => v.id === i.variantId)
          const attrs = variant?.attributes ? Object.values(variant.attributes).join(' · ') : ''
          map[i.variantId] = [p.name, attrs].filter(Boolean).join(' · ')
        })
    })
    return map
  }, [results, items, ids])
}
