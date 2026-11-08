import { getApiError } from '@/api/client'

export type SearchFailure = 'tooLarge' | 'invalidImage' | 'busy' | 'unavailable' | 'network'

/** Maps a failed image-search request to one of the "Chưa xử lý được ảnh" variants. */
export function classifyFailure(error: unknown): SearchFailure {
  const { code, status } = getApiError(error)
  if (status === 413 || code === 'IMAGE_TOO_LARGE') return 'tooLarge'
  if (code === 'INVALID_IMAGE') return 'invalidImage'
  if (status === 429) return 'busy'
  if (status === 0) return 'network' // offline, timeout or aborted
  return 'unavailable' // 5xx (AI service / Elasticsearch down) and unexpected 4xx
}
