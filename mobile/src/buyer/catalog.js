import { useEffect, useState } from 'react'
import { getProduct, getShop } from '../api/shopApi'

// Gio hang va don hang chi luu id (productId, variantId, shopId): ten, anh, phan loai doc tu API
// cong khai va cache theo id trong phien (giong web hooks/useCatalogLookup).
const productCache = new Map()
const shopCache = new Map()

function cached(cache, id, load) {
  if (!cache.has(id)) {
    cache.set(
      id,
      load(id).then(
        (value) => ({ status: 'ready', value }),
        (error) => {
          // Loi mang: cho thu lai lan sau; 404 (an / xoa) thi nho luon.
          if (error?.response?.status !== 404) cache.delete(id)
          return { status: 'missing', value: null }
        },
      ),
    )
  }
  return cache.get(id)
}

const loadProduct = async (id) => (await getProduct(id)).product

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
      if (current) setEntries(Object.fromEntries(list.map((id, i) => [id, results[i]])))
    })
    return () => {
      current = false
    }
  }, [cache, key, load])
  return entries
}

/** {[productId]: {status, value: ProductResponse|null}}; chua co key = dang tai. */
export const useProductLookup = (ids) => useLookup(productCache, ids, loadProduct)

/** {[shopId]: {status, value: {name, ...}|null}}. */
export const useShopLookup = (ids) => useLookup(shopCache, ids, getShop)

const money = new Intl.NumberFormat('vi-VN')
/** "249.000đ" theo cach viet trong Figma. */
export const formatPrice = (value) => `${money.format(Math.round(Number(value || 0)))}đ`

/** Anh dau tien la URL http(s) hop le (anh tuong doi / rong thi khong hien). */
export const productImage = (product) =>
  (product?.imageUrls || []).find((url) => /^https?:\/\//i.test(url || '')) || null

/** "Navy · L" tu thuoc tinh phan loai. */
export const variantLabel = (variant) =>
  variant?.attributes ? Object.values(variant.attributes).filter(Boolean).join(' · ') : ''

export function findVariant(product, variantId) {
  return (product?.variants || []).find((variant) => variant.id === variantId) || null
}

/** Ten dong hang: "Ten san pham / Phan loai"; chua tai xong thi "Dang tai san pham…". */
export function lineName(lookup, productId, variantId) {
  const entry = lookup[productId]
  if (!entry) return 'Đang tải sản phẩm…'
  if (!entry.value) return 'Sản phẩm không còn bán'
  const variant = variantLabel(findVariant(entry.value, variantId))
  return variant ? `${entry.value.name} / ${variant}` : entry.value.name
}

/** Gia hien cho san pham: "249.000đ" hoac "199.000đ – 249.000đ". */
export function priceRange(product) {
  const min = product?.minPrice
  const max = product?.maxPrice
  if (min == null) return ''
  return max != null && max !== min ? `${formatPrice(min)} – ${formatPrice(max)}` : formatPrice(min)
}

export const ORDER_STATUS_LABELS = Object.freeze({
  WAITING_PAYMENT: 'Chờ thanh toán',
  PENDING: 'Chờ xác nhận',
  PROCESSING: 'Đang chuẩn bị',
  SHIPPED: 'Đang giao',
  DELIVERED: 'Đã giao',
  COMPLETED: 'Hoàn thành',
  CANCELLED: 'Đã huỷ',
  DELIVERY_FAILED: 'Giao không thành công, đang hoàn về shop',
  RETURNED_TO_SELLER: 'Đã hoàn trả cho shop',
})

export const PAYMENT_METHOD_LABELS = Object.freeze({
  COD: 'Thanh toán khi nhận hàng (COD)',
  VNPAY: 'VNPay',
})

export const PAYMENT_STATUS_LABELS = Object.freeze({
  CREATED: 'Đang tạo liên kết thanh toán',
  CREATING: 'Đang tạo liên kết thanh toán',
  PENDING: 'Chờ thanh toán',
  AWAITING_WEBHOOK: 'Đang xác nhận với ngân hàng',
  RECONCILING: 'Đang đối soát',
  SUCCEEDED: 'Đã thanh toán',
  FAILED: 'Thanh toán thất bại',
  EXPIRED: 'Hết hạn thanh toán',
  CANCELLED: 'Đã huỷ',
  COD_PENDING: 'Thu tiền khi giao',
  COD_COLLECTED: 'Đã thu tiền khi giao',
})

export const shortOrderId = (id) => (id ? `#${String(id).slice(-8).toUpperCase()}` : '')

/** Cung quy tac voi web: huy khi don con cho thanh toan / cho xac nhan (backend kiem tra lai). */
export const canCancelOrder = (order) => ['WAITING_PAYMENT', 'PENDING'].includes(order?.status)

/** Theo doi giao hang co y nghia tu khi don da ban giao. */
export const hasDelivery = (order) =>
  ['PROCESSING', 'SHIPPED', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNED_TO_SELLER'].includes(
    order?.status,
  )
