import type { ProductSummary } from '@/api/catalog'
import { api } from '@/api/client'

/** Personalised (or popularity-based) feed. Sends the token when signed in. */
export async function getForYou(limit = 10): Promise<ProductSummary[]> {
  const { data } = await api.get<{ items: ProductSummary[] }>('/api/ai/recommendations/for-you', {
    params: { limit },
  })
  return data.items
}

export async function getSimilar(productId: string, limit = 10): Promise<ProductSummary[]> {
  const { data } = await api.get<{ items: ProductSummary[] }>(
    `/api/ai/recommendations/similar/${encodeURIComponent(productId)}`,
    { skipAuth: true, params: { limit } },
  )
  return data.items
}
