import apiClient from './apiClient'

// Cart Service (PBL6-16): gio nam tren server (Redis) theo userId trong token, nen web va
// mobile cung doc mot gio. Gia (unitPrice) la gia luc them vao gio; /validate doi chieu lai
// voi Product Service truoc khi thanh toan.

export const CART_LIMITS = Object.freeze({ maxQuantity: 999, maxLines: 50 })

const itemUrl = (productId) => `/api/cart/items/${encodeURIComponent(productId)}`
const variantParams = (variantId) => (variantId ? { params: { variantId } } : undefined)

export const getCart = async () => (await apiClient.get('/api/cart')).data

export const addCartItem = async ({ productId, variantId, shopId, quantity, unitPrice }) =>
  (
    await apiClient.post('/api/cart/items', {
      productId,
      variantId: variantId || null,
      shopId,
      quantity,
      unitPrice,
    })
  ).data

export const updateCartItemQuantity = async (productId, variantId, quantity) =>
  (await apiClient.patch(itemUrl(productId), { quantity }, variantParams(variantId))).data

export const removeCartItem = async (productId, variantId) =>
  (await apiClient.delete(itemUrl(productId), variantParams(variantId))).data

export const clearCart = async () => {
  await apiClient.delete('/api/cart')
}

/** {ok, issues[{productId, variantId, type, message, cartPrice, currentPrice, availableStock}]} */
export const validateCart = async () => (await apiClient.post('/api/cart/validate')).data
