import { router, type Href } from 'expo-router'

export const IMAGE_SEARCH_ROUTES = {
  index: '/image-search',
  permission: '/image-search/permission',
  camera: '/image-search/camera',
  preview: '/image-search/preview',
  results: '/image-search/results',
  filter: '/image-search/filter',
} as const

export const href = (route: keyof typeof IMAGE_SEARCH_ROUTES) => IMAGE_SEARCH_ROUTES[route] as Href

export function goBackOrHome() {
  if (router.canGoBack()) router.back()
  else router.replace('/(buyer)')
}

export function openProduct(id: string) {
  router.push(`/product/${id}` as Href)
}
