import apiClient from './apiClient'

// Danh muc va danh sach san pham la API cong khai: khong gui token de mot phien het han
// khong lam hong trang chu cua khach.
const publicRequest = { skipAuth: true }

export const PRODUCT_SORT = Object.freeze({
  newest: 'NEWEST',
  bestSelling: 'BEST_SELLING',
  priceAsc: 'PRICE_ASC',
  priceDesc: 'PRICE_DESC',
})

export const listCategories = async () =>
  (await apiClient.get('/api/products/categories', publicRequest)).data

// Chua loc theo thanh pho: Product Service chua co tham so city/province va VMarket hien chi
// hoat dong tai mot thanh pho (src/data/city.js). Khi backend bo sung, truyen ACTIVE_CITY tai day.
// Product Service khong co bo loc ton kho: chi san pham dang ban moi xuat hien.
export async function browseProducts({
  keyword,
  categoryId,
  minPrice,
  maxPrice,
  minRating,
  shopId,
  sort = PRODUCT_SORT.newest,
  page = 0,
  size = 20,
} = {}) {
  const params = { sort, page, size }
  if (keyword?.trim()) params.q = keyword.trim()
  if (categoryId) params.categoryId = categoryId
  if (shopId) params.shopId = shopId
  if (Number.isFinite(minPrice)) params.minPrice = minPrice
  if (Number.isFinite(maxPrice)) params.maxPrice = maxPrice
  if (Number.isFinite(minRating) && minRating > 0) params.minRating = minRating
  return (await apiClient.get('/api/products', { ...publicRequest, params })).data
}

export const listProducts = async ({ shopId, page = 0, size = 20, sort = 'NEWEST' } = {}) =>
  (
    await apiClient.get('/api/products', {
      ...publicRequest,
      params: { shopId, page, size, sort },
    })
  ).data

/** {product, similarProducts}; 404 khi san pham an, bi go hoac shop bi dinh chi. */
export const getProduct = async (productId) =>
  (await apiClient.get(`/api/products/${encodeURIComponent(productId)}`, publicRequest)).data
