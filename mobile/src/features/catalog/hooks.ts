import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

import {
  browseProducts,
  getProductDetail,
  getShop,
  listCategories,
  searchProducts,
  suggestKeywords,
  type ProductQuery,
  type ProductSummary,
} from '@/api/catalog'
import { getForYou, getSimilar } from '@/api/recommendations'
import { useAuth } from '@/auth/AuthProvider'

const MINUTE = 60_000

export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return debounced
}

export function useCategories() {
  return useQuery({
    queryKey: ['catalog', 'categories'],
    queryFn: listCategories,
    staleTime: 10 * MINUTE,
  })
}

export function useShop(shopId?: string) {
  return useQuery({
    queryKey: ['catalog', 'shop', shopId],
    queryFn: () => getShop(shopId as string),
    enabled: !!shopId,
    staleTime: 10 * MINUTE,
    retry: false,
  })
}

/** Home feed: for-you when signed in, otherwise (or when empty/failing) best sellers. */
export function useHomeFeed() {
  const { status, user } = useAuth()
  return useQuery({
    queryKey: ['catalog', 'home-feed', status === 'authenticated' ? user?.userId : 'guest'],
    enabled: status !== 'loading',
    staleTime: MINUTE,
    queryFn: async (): Promise<{ personal: boolean; items: ProductSummary[] }> => {
      if (status === 'authenticated') {
        try {
          const items = await getForYou(10)
          if (items.length) return { personal: true, items }
        } catch {
          // recommendation-service is optional: fall through to best sellers
        }
      }
      const page = await browseProducts({ sort: 'BEST_SELLING', size: 10 })
      return { personal: false, items: page.items }
    },
  })
}

export function useNewestProducts() {
  return useQuery({
    queryKey: ['catalog', 'newest'],
    staleTime: MINUTE,
    queryFn: async () => (await browseProducts({ sort: 'NEWEST', size: 6 })).items,
  })
}

export function useBestSellers(categoryId?: string, enabled = true) {
  return useQuery({
    queryKey: ['catalog', 'best-sellers', categoryId ?? null],
    enabled,
    staleTime: MINUTE,
    queryFn: async () =>
      (await browseProducts({ sort: 'BEST_SELLING', categoryId, size: 3 })).items,
  })
}

export function useProductSearch(query: ProductQuery) {
  return useInfiniteQuery({
    queryKey: ['catalog', 'search', query],
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) =>
      searchProducts({ ...query, page: pageParam, size: 20 }, signal),
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  })
}

/** Autocomplete lines + top matching products for the search-entry screen. */
export function useSearchPreview(keyword: string) {
  const term = keyword.trim()
  return useQuery({
    queryKey: ['catalog', 'search-preview', term],
    enabled: term.length >= 2,
    staleTime: MINUTE,
    retry: false,
    queryFn: async ({ signal }) => {
      const [names, page] = await Promise.all([
        suggestKeywords(term, signal).catch(() => null),
        searchProducts({ keyword: term, size: 3 }, signal),
      ])
      const source = names ?? page.items.map((p) => p.name)
      const suggestions = [...new Set(source.map((n) => n.toLowerCase()))].slice(0, 5)
      return { suggestions, products: page.items }
    },
  })
}

export function useProductDetail(id: string) {
  return useQuery({
    queryKey: ['catalog', 'product', id],
    queryFn: () => getProductDetail(id),
    enabled: !!id,
    retry: (count, error) =>
      (error as { response?: { status?: number } }).response?.status !== 404 && count < 2,
  })
}

/** Recommendation-service similar items, falling back to the detail response's list. */
export function useSimilarProducts(id: string) {
  const detail = useProductDetail(id)
  const similar = useQuery({
    queryKey: ['catalog', 'similar', id],
    enabled: !!id && detail.isSuccess,
    staleTime: MINUTE,
    retry: false,
    queryFn: async () => {
      try {
        const items = await getSimilar(id, 10)
        if (items.length) return items
      } catch {
        // fall back below
      }
      return detail.data?.similarProducts ?? []
    },
  })
  return { detail, similar }
}
