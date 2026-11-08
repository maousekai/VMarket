import { Platform } from 'react-native'

import { api } from '@/api/client'

/** Limits enforced by ai-search-service (contract.py: MAX_FILE). */
export const MAX_IMAGE_BYTES = 10_000_000

const UPLOAD_NAME = 'search.jpg'
const UPLOAD_MIME = 'image/jpeg'
/** Server accepts the upload for 10 s, then needs time to embed the image and query the index. */
const REQUEST_TIMEOUT_MS = 25_000

export interface ImageSearchFilter {
  categoryId?: string
  /** Display name of `categoryId` (not sent to the server). */
  categoryName?: string
  minPrice?: number
  maxPrice?: number
}

export interface ImageSearchHit {
  id: string
  shopId: string
  name: string
  description?: string | null
  imageUrls: string[]
  categoryId?: string | null
  currency: string
  minPrice: number
  maxPrice: number
  ratingAverage?: number | null
  ratingCount?: number | null
  soldCount?: number | null
  availableStock?: number | null
  score?: number
  matchedImageUrl?: string | null
  /** 0..1, results arrive sorted by it (descending). */
  similarity: number
}

export interface ImageSearchResponse {
  results: ImageSearchHit[]
  limit: number
  totalReturned: number
}

export interface Category {
  id: string
  name: string
  parentId?: string | null
  active?: boolean
  sortOrder?: number
  children?: Category[]
}

/**
 * The multipart part must be named `file`. React Native FormData takes a `{ uri, name, type }`
 * descriptor and streams the file natively; a browser FormData needs a real Blob.
 */
async function buildForm(uri: string): Promise<FormData> {
  const form = new FormData()
  if (Platform.OS === 'web') {
    const blob = await (await fetch(uri)).blob()
    form.append('file', blob, UPLOAD_NAME)
  } else {
    form.append('file', { uri, name: UPLOAD_NAME, type: UPLOAD_MIME } as unknown as Blob)
  }
  return form
}

/** POST /api/ai/search/image (public). Rejects with an axios error. */
export async function searchByImage(
  uri: string,
  filter: ImageSearchFilter,
  signal?: AbortSignal,
): Promise<ImageSearchResponse> {
  const form = await buildForm(uri)
  const res = await api.post<ImageSearchResponse>('/api/ai/search/image', form, {
    params: {
      categoryId: filter.categoryId,
      minPrice: filter.minPrice,
      maxPrice: filter.maxPrice,
    },
    // Overrides the client's JSON default; the boundary is appended by the platform.
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: REQUEST_TIMEOUT_MS,
    skipAuth: true,
    signal,
  })
  return res.data
}

/** GET /api/products/categories (public) -> category tree. */
export async function listCategories(): Promise<Category[]> {
  return (await api.get<Category[]>('/api/products/categories', { skipAuth: true })).data
}
