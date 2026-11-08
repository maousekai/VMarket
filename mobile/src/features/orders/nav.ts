import { router, type Href } from 'expo-router'

/** Back button handler: pop when possible (deep links land with no history), else go to `fallback`. */
export const backOr = (fallback: string) => () =>
  router.canGoBack() ? router.back() : router.replace(fallback as Href)

export const goHome = () => router.replace('/(buyer)')

export const orderPath = (id: string) => `/orders/${id}` as Href
export const orderSubPath = (id: string, sub: string) => `/orders/${id}/${sub}` as Href
export const returnPath = (id: string) => `/returns/${id}` as Href
export const reviewPath = (orderId: string, itemId?: string) =>
  (itemId ? `/reviews/${orderId}?itemId=${itemId}` : `/reviews/${orderId}`) as Href

export const ORDERS = '/orders' as Href
