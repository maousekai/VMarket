import { useCallback } from 'react'
import useRemoteData from './useRemoteData'
import { browseProducts, listCategories, PRODUCT_SORT } from '../services/api/productApi'
import { getForYou } from '../services/api/recommendationApi'
import { getPublicShop } from '../services/api/shopApi'

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

/** Ket qua tim theo tu khoa/danh muc tu Product Service (tam thoi, truoc khi co Search Service). */
export function useProductSearch({ keyword, categoryId, sort }) {
  const key = JSON.stringify([keyword, categoryId, sort])
  const load = useCallback(
    () => browseProducts({ keyword, categoryId, sort, size: 20 }),
    [keyword, categoryId, sort],
  )
  return useRemoteData(load, key)
}
