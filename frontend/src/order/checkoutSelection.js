// Dong gio duoc chon de dat hang. Giu trong sessionStorage de F5 trang thanh toan van con.
const KEY = 'vmarket.checkoutLines'

export function saveCheckoutLines(keys) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(keys))
  } catch {
    // Private mode: router state still carries the selection.
  }
}

export function loadCheckoutLines() {
  try {
    const value = JSON.parse(sessionStorage.getItem(KEY) || '[]')
    return Array.isArray(value) ? value : []
  } catch {
    return []
  }
}

export function clearCheckoutLines() {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // ignore
  }
}

/** "productId::variantId" -> {productId, variantId} cho POST /api/orders/checkout. */
export const lineToItem = (key) => {
  const [productId, variantId] = key.split('::')
  return { productId, variantId: variantId || null }
}
