import { api, getApiError } from '@/api/client'

export type SortKey = 'RELEVANCE' | 'BEST_SELLING' | 'PRICE_ASC' | 'PRICE_DESC' | 'NEWEST'

/** Subset of ProductResponse returned by search and recommendations. */
export interface ProductSummary {
  id: string
  shopId: string
  name: string
  description?: string
  imageUrls: string[]
  categoryId: string
  currency: string
  minPrice: number | null
  maxPrice: number | null
  ratingAverage: number
  ratingCount: number
  soldCount: number
  availableStock: number
}

export interface Variant {
  id: string
  sku: string
  attributes: Record<string, string>
  price: number
  currency: string
  stock: number
  reservedStock: number
  availableStock: number
  soldCount: number
}

export interface Product extends ProductSummary {
  brandId: string | null
  status: 'DRAFT' | 'ACTIVE' | 'HIDDEN'
  variants: Variant[]
  moderationRemoved: boolean
}

export interface ProductDetail {
  product: Product
  similarProducts: ProductSummary[]
}

export interface Category {
  id: string
  name: string
  slug: string
  parentId: string | null
  active: boolean
  sortOrder: number
  children: Category[]
}

export interface Shop {
  id: string
  name: string
  description: string | null
  logoUrl: string | null
  coverUrl: string | null
  policies: string | null
  province: string | null
}

export interface ProductPage {
  items: ProductSummary[]
  page: number
  totalPages: number
  totalElements: number
}

export interface ProductQuery {
  keyword?: string
  categoryId?: string
  minPrice?: number
  maxPrice?: number
  minRating?: number
  sort?: SortKey
  page?: number
  size?: number
}

interface BrowsePage {
  content: ProductSummary[]
  page: number
  totalPages: number
  totalElements: number
}

interface AiSearchPage {
  results: ProductSummary[]
  page: number
  totalPages: number
  totalElements: number
}

const PUBLIC = { skipAuth: true } as const

export async function listCategories(): Promise<Category[]> {
  return (await api.get<Category[]>('/api/products/categories', PUBLIC)).data
}

export async function getProductDetail(id: string): Promise<ProductDetail> {
  return (await api.get<ProductDetail>(`/api/products/${encodeURIComponent(id)}`, PUBLIC)).data
}

export async function getShop(shopId: string): Promise<Shop> {
  return (await api.get<Shop>(`/api/shops/${encodeURIComponent(shopId)}`, PUBLIC)).data
}

/** product-service listing: browse, category pages and the fallback for keyword search. */
export async function browseProducts(
  query: ProductQuery,
  signal?: AbortSignal,
): Promise<ProductPage> {
  // product-service has no relevance ranking: popular for text search, newest for plain browsing.
  const sort =
    query.sort === 'RELEVANCE' ? (query.keyword?.trim() ? 'BEST_SELLING' : 'NEWEST') : query.sort
  const { data } = await api.get<BrowsePage>('/api/products', {
    ...PUBLIC,
    signal,
    params: {
      q: query.keyword?.trim() || undefined,
      categoryId: query.categoryId,
      minPrice: query.minPrice,
      maxPrice: query.maxPrice,
      minRating: query.minRating,
      sort,
      page: query.page ?? 0,
      size: query.size ?? 20,
    },
  })
  return {
    items: data.content,
    page: data.page,
    totalPages: data.totalPages,
    totalElements: data.totalElements,
  }
}

/** The AI search service is optional infrastructure: treat these failures as "service down". */
function isSearchUnavailable(error: unknown): boolean {
  const { status } = getApiError(error)
  return status === 0 || status >= 500 || status === 404
}

async function aiSearch(query: ProductQuery, signal?: AbortSignal): Promise<ProductPage> {
  const { data } = await api.get<AiSearchPage>('/api/ai/search', {
    ...PUBLIC,
    signal,
    params: {
      keyword: query.keyword?.trim(),
      categoryId: query.categoryId,
      minPrice: query.minPrice,
      maxPrice: query.maxPrice,
      sort: query.sort ?? 'RELEVANCE',
      page: query.page ?? 0,
      size: query.size ?? 20,
    },
  })
  return {
    items: data.results,
    page: data.page,
    totalPages: data.totalPages,
    totalElements: data.totalElements,
  }
}

/**
 * Keyword search through ai-search-service, falling back to product-service when it is down.
 * Without a keyword, or with a rating filter (unsupported by ai-search), product-service is used.
 */
export async function searchProducts(
  query: ProductQuery,
  signal?: AbortSignal,
): Promise<ProductPage> {
  if (!query.keyword?.trim() || query.minRating) return browseProducts(query, signal)
  try {
    return await aiSearch(query, signal)
  } catch (error) {
    if (!isSearchUnavailable(error)) throw error
    return browseProducts(query, signal)
  }
}

/** Autocomplete product names. Empty for keywords shorter than 2 characters. */
export async function suggestKeywords(keyword: string, signal?: AbortSignal): Promise<string[]> {
  const { data } = await api.get<{ results: { id: string; name: string }[] }>(
    '/api/ai/search/suggestions',
    { ...PUBLIC, signal, params: { keyword: keyword.trim(), limit: 5 } },
  )
  return data.results.map((r) => r.name)
}
