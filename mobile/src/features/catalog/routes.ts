import type { Href } from 'expo-router'

/** Catalog route builders. Cast because typed routes are generated on `expo start`. */
type Params = Record<string, string | undefined>

const route = (pathname: string, params?: Params) =>
  (params ? { pathname, params } : pathname) as unknown as Href

export const searchHref = (params?: Params) => route('/(buyer)/search', params)
export const resultsHref = (params?: Params) => route('/(buyer)/search/results', params)
export const filterHref = (params?: Params) => route('/(buyer)/search/filter', params)
export const productHref = (id: string) => route(`/(buyer)/product/${id}`)
export const similarHref = (id: string) => route(`/(buyer)/product/${id}/similar`)
export const reviewsHref = (id: string) => route(`/(buyer)/product/${id}/reviews`)
export const imageSearchHref = route('/(buyer)/image-search')
export const notificationsHref = route('/(buyer)/notifications')
export const assistantHref = route('/(buyer)/assistant')
