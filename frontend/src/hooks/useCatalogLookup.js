import { useEffect, useState } from 'react'
import { getProduct } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'

// Gio hang va don hang chi luu id (productId, variantId, shopId). Ten, anh, thuoc tinh
// duoc doc tu API cong khai; cache theo id trong phien de khong goi lai khi render lai.
const productCache = new Map()
const shopCache = new Map()

function cached(cache, id, load) {
  if (!cache.has(id)) {
    cache.set(
      id,
      load(id).then(
        (value) => ({ status: 'ready', value }),
        (error) => {
          // Loi mang thi cho phep thu lai lan sau; 404 (da an / da xoa) thi nho luon.
          if (error?.response?.status !== 404) cache.delete(id)
          return { status: 'missing', value: null }
        },
      ),
    )
  }
  return cache.get(id)
}

const loadProduct = async (id) => (await getProduct(id)).product

/** Xoa cache (dung trong test hoac sau khi seller sua san pham). */
export function resetCatalogLookup() {
  productCache.clear()
  shopCache.clear()
}

function useLookup(cache, ids, load) {
  const key = [...new Set(ids.filter(Boolean))].sort().join(',')
  const [entries, setEntries] = useState({})
  useEffect(() => {
    if (!key) return undefined
    let current = true
    const list = key.split(',')
    Promise.all(list.map((id) => cached(cache, id, load))).then((results) => {
      if (!current) return
      setEntries(Object.fromEntries(list.map((id, index) => [id, results[index]])))
    })
    return () => {
      current = false
    }
  }, [cache, key, load])
  return entries
}

/** {[productId]: {status: 'ready'|'missing', value: ProductResponse|null}}; chua co = dang tai. */
export const useProductLookup = (productIds) => useLookup(productCache, productIds, loadProduct)

/** {[shopId]: {status, value: PublicShopResponse|null}}. */
export const useShopLookup = (shopIds) => useLookup(shopCache, shopIds, getPublicShop)
