import { useCallback } from 'react'
import useRemoteData from './useRemoteData'
import { browseProducts, listCategories, PRODUCT_SORT } from '../services/api/productApi'
import { getForYou } from '../services/api/recommendationApi'
import { isSearchUnavailable, searchProducts, SEARCH_SORT } from '../services/api/searchApi'
import { getPublicShop } from '../services/api/shopApi'
import { sortTree } from '../utils/category'

const HOME_PRODUCT_COUNT = 8
const FEATURED_SHOP_COUNT = 8

const bestSelling = async (size) =>
  (await browseProducts({ sort: PRODUCT_SORT.bestSelling, size }))?.content ?? []

/** Danh muc cap 1 dang hien thi, theo thu tu admin sap xep. */
export function useTopCategories() {
  const load = useCallback(async () => {
    const tree = await listCategories()
    return (Array.isArray(tree) ? tree : [])
      .filter((category) => category.active !== false && !category.parentId)
      .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  }, [])
  return useRemoteData(load, 'top-categories')
}

/**
 * Luoi san pham trang chu. Nguoi da dang nhap thu goi y ca nhan truoc; neu Recommendation
 * loi hoac chua co du lieu (cold start) thi dung danh sach ban chay de trang khong trong.
 */
export function useHomeProducts(userId) {
  const load = useCallback(async () => {
    if (userId) {
      try {
        const items = await getForYou({ limit: HOME_PRODUCT_COUNT })
        if (items.length) return { source: 'personal', products: items }
      } catch {
        // Fall through to best sellers: personal picks are an enhancement, not a requirement.
      }
    }
    return { source: 'popular', products: await bestSelling(HOME_PRODUCT_COUNT) }
  }, [userId])
  return useRemoteData(load, userId || 'guest')
}

/**
 * Shop Service chua co API danh sach cong khai, nen lay gian hang cua cac san pham ban chay
 * roi doc ho so cong khai tung gian hang. Gian hang doc loi thi bo qua, khong lam hong ca khu.
 */
export function useFeaturedShops() {
  const load = useCallback(async () => {
    const products = await bestSelling(40)
    const shopIds = [...new Set(products.map((product) => product.shopId).filter(Boolean))].slice(
      0,
      FEATURED_SHOP_COUNT,
    )
    const results = await Promise.allSettled(shopIds.map((id) => getPublicShop(id)))
    const shops = results.filter((r) => r.status === 'fulfilled' && r.value).map((r) => r.value)
    if (shopIds.length && !shops.length) throw results.find((r) => r.status === 'rejected').reason
    return shops
  }, [])
  return useRemoteData(load, 'featured-shops')
}

/** Cay danh muc dang hien thi (nhieu cap), da sap theo sortOrder. */
export function useCategoryTree() {
  const load = useCallback(async () => {
    const tree = await listCategories()
    return sortTree(Array.isArray(tree) ? tree : [])
  }, [])
  return useRemoteData(load, 'category-tree')
}

export const SEARCH_PAGE_SIZE = 20

/**
 * Ket qua trang /search. `filters` = {keyword, categoryId, minPrice, maxPrice, minRating, sort, page}.
 * - Co tu khoa: AI Search (xep theo do lien quan). Search tam ngung thi tim co ban theo ten o
 *   Product Service va danh dau `fallback` (UC-03 luong thay the).
 * - Khong co tu khoa (duyet danh muc, ban chay...): Product Service nhu truoc.
 */
export function useProductSearch(filters) {
  const key = JSON.stringify(filters)
  const load = useCallback(async () => {
    const { keyword, minRating, ...rest } = JSON.parse(key)
    if (!keyword) {
      return browseProducts({ ...rest, minRating, size: SEARCH_PAGE_SIZE })
    }
    try {
      return {
        ...(await searchProducts({ keyword, ...rest, size: SEARCH_PAGE_SIZE })),
        fallback: false,
      }
    } catch (error) {
      if (!isSearchUnavailable(error)) throw error
      // Product Service khong co "lien quan": dung ban chay, gan nhat voi cach Search xep hang.
      const sort = rest.sort === SEARCH_SORT.relevance ? PRODUCT_SORT.bestSelling : rest.sort
      const page = await browseProducts({ keyword, ...rest, sort, size: SEARCH_PAGE_SIZE })
      return { ...page, fallback: true }
    }
  }, [key])
  return useRemoteData(load, key)
}

const FALLBACK_BEST_SELLER_COUNT = 8

/**
 * San pham ban chay goi y khi tim khong co ket qua (UC-03): theo danh muc dang loc, neu
 * khong loc thi toan san. `enabled=false` thi khong goi API.
 */
export function useBestSellers(categoryId, enabled) {
  const load = useCallback(async () => {
    if (!enabled) return []
    const page = await browseProducts({
      categoryId: categoryId || undefined,
      sort: PRODUCT_SORT.bestSelling,
      size: FALLBACK_BEST_SELLER_COUNT,
    })
    return page?.content ?? []
  }, [categoryId, enabled])
  return useRemoteData(load, `best-sellers:${enabled ? categoryId || 'all' : 'off'}`)
}
