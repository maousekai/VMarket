import apiClient from './apiClient'

// AI Search Service (PBL6-21) qua gateway: cac route deu cong khai, khong gui token de mot
// phien het han khong lam hong tim kiem cua nguoi dung.
const publicRequest = { skipAuth: true }

export const SEARCH_SORT = Object.freeze({
  relevance: 'RELEVANCE',
  newest: 'NEWEST',
  bestSelling: 'BEST_SELLING',
  priceAsc: 'PRICE_ASC',
  priceDesc: 'PRICE_DESC',
})

// Gioi han trong docs/search-contract.json.
export const SEARCH_LIMITS = Object.freeze({
  keyword: 200,
  minSuggestionLength: 2,
  maxImageBytes: 10_000_000,
  maxResultWindow: 10_000,
})

const SUGGESTION_COUNT = 8
const IMAGE_RESULT_COUNT = 20
// Server tu cat upload o 10 s; them bien do cho mang cham va thoi gian tinh embedding.
const IMAGE_TIMEOUT_MS = 30_000

/**
 * Search tu choi moi tham so la hoac rong (400 INVALID_QUERY) nen chi gui gia tri co that.
 */
function filterParams({ categoryId, minPrice, maxPrice }) {
  const params = {}
  if (categoryId) params.categoryId = categoryId
  if (Number.isFinite(minPrice)) params.minPrice = minPrice
  if (Number.isFinite(maxPrice)) params.maxPrice = maxPrice
  return params
}

/**
 * Tim theo tu khoa, xep theo do lien quan. Tra ve cung dang trang voi Product Service
 * (`content`) de dung chung luoi san pham va phan trang.
 */
export async function searchProducts({
  keyword,
  categoryId,
  minPrice,
  maxPrice,
  sort = SEARCH_SORT.relevance,
  page = 0,
  size = 20,
} = {}) {
  const params = {
    keyword: keyword.trim().slice(0, SEARCH_LIMITS.keyword),
    ...filterParams({ categoryId, minPrice, maxPrice }),
    sort,
    page,
    size,
  }
  const { data } = await apiClient.get('/api/ai/search', { ...publicRequest, params })
  // Search chi cho xem toi ket qua thu 10.000 ((page+1)*size <= 10000).
  const maxPages = Math.floor(SEARCH_LIMITS.maxResultWindow / size)
  return {
    content: Array.isArray(data?.results) ? data.results : [],
    totalElements: data?.totalElements ?? 0,
    totalPages: Math.min(data?.totalPages ?? 0, maxPages),
    page: data?.page ?? page,
    size: data?.size ?? size,
  }
}

/** Ten san pham goi y khi go; duoi 2 ky tu khong goi API (server cung tra rong). */
export async function suggestKeywords(keyword, { signal } = {}) {
  const value = keyword.trim().slice(0, SEARCH_LIMITS.keyword)
  if (value.length < SEARCH_LIMITS.minSuggestionLength) return []
  const { data } = await apiClient.get('/api/ai/search/suggestions', {
    ...publicRequest,
    params: { keyword: value, limit: SUGGESTION_COUNT },
    signal,
  })
  return Array.isArray(data?.results) ? data.results : []
}

/** San pham tuong dong voi anh (JPEG/PNG/WebP, <= 10.000.000 byte), da sap theo do giong. */
export async function searchByImage(file, filters = {}, { signal } = {}) {
  const body = new FormData()
  body.append('file', file)
  // Khong dat Content-Type: trinh duyet tu them boundary cua multipart.
  const { data } = await apiClient.post('/api/ai/search/image', body, {
    ...publicRequest,
    params: { ...filterParams(filters), limit: IMAGE_RESULT_COUNT },
    timeout: IMAGE_TIMEOUT_MS,
    signal,
  })
  return Array.isArray(data?.results) ? data.results : []
}

/**
 * Search Service tam ngung: giao dien chuyen sang tim co ban theo ten o Product Service
 * (UC-03 luong thay the). Tinh moi loi 5xx va mat mang: khi container Search tat, gateway
 * tra 500 (ResourceAccessException) chu khong phai 503.
 */
export function isSearchUnavailable(error) {
  const status = error?.response?.status
  if (!status) return error?.code !== 'ERR_CANCELED'
  return status >= 500
}
