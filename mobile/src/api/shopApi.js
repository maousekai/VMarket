import client from './client'

// API mua hang cho nguoi mua (cung endpoint web dang dung): san pham, gio hang, dat don, thanh
// toan VNPay / COD, dia chi va ho so. Tat ca di qua API Gateway.

/** Khoa chong gui trung: cung mot thao tac gui lai thi dung lai khoa, thao tac moi tao khoa moi. */
export function newIdempotencyKey() {
  const random = () =>
    Math.floor(Math.random() * 0x100000000)
      .toString(16)
      .padStart(8, '0')
  return `${Date.now().toString(16)}${random()}${random()}`.slice(0, 32)
}

const keyed = (key) => ({ headers: { 'Idempotency-Key': key || newIdempotencyKey() } })
const enc = encodeURIComponent

// ---------------------------------------------------------------- san pham

export const PRODUCT_SORT = Object.freeze({
  relevant: 'NEWEST',
  bestSelling: 'BEST_SELLING',
  priceAsc: 'PRICE_ASC',
  priceDesc: 'PRICE_DESC',
})

/** {content, page, size, totalElements, totalPages} */
export async function browseProducts({
  keyword,
  categoryId,
  minPrice,
  maxPrice,
  sort = PRODUCT_SORT.relevant,
  page = 0,
  size = 20,
  signal,
} = {}) {
  const params = { sort, page, size }
  if (keyword?.trim()) params.q = keyword.trim()
  if (categoryId) params.categoryId = categoryId
  if (Number.isFinite(minPrice)) params.minPrice = minPrice
  if (Number.isFinite(maxPrice)) params.maxPrice = maxPrice
  return (await client.get('/api/products', { params, signal })).data
}

export const listCategories = async () => (await client.get('/api/products/categories')).data

/** {product, similarProducts}; 404 khi san pham an hoac bi go. */
export const getProduct = async (productId, signal) =>
  (await client.get(`/api/products/${enc(productId)}`, { signal })).data

export const getShop = async (shopId) => (await client.get(`/api/shops/${enc(shopId)}`)).data

// ---------------------------------------------------------------- gio hang

/** {groups:[{shopId, items:[{productId, variantId, shopId, quantity, unitPrice, lineTotal}], subtotal}], totalQuantity, totalAmount} */
export const getCart = async () => (await client.get('/api/cart')).data

export const addCartItem = async ({ productId, variantId, shopId, quantity }) =>
  (
    await client.post('/api/cart/items', {
      productId,
      variantId: variantId || null,
      shopId,
      quantity,
    })
  ).data

const itemConfig = (variantId) => (variantId ? { params: { variantId } } : undefined)

export const updateCartItem = async (productId, variantId, quantity) =>
  (await client.patch(`/api/cart/items/${enc(productId)}`, { quantity }, itemConfig(variantId)))
    .data

export const removeCartItem = async (productId, variantId) =>
  (await client.delete(`/api/cart/items/${enc(productId)}`, itemConfig(variantId))).data

/** {ok, issues:[{productId, variantId, type, message, cartPrice, currentPrice, availableStock}]} */
export const validateCart = async () => (await client.post('/api/cart/validate')).data

// ---------------------------------------------------------------- don hang

export const PAYMENT_METHOD = Object.freeze({ cod: 'COD', vnpay: 'VNPAY' })

/** Moi gian hang mot don; tra danh sach don vua tao. */
export const checkoutOrders = async ({ addressId, note, paymentMethod, items }, key) =>
  (
    await client.post(
      '/api/orders/checkout',
      { addressId, note: note?.trim() || null, paymentMethod, items },
      keyed(key),
    )
  ).data

export const listMyOrders = async (signal) => (await client.get('/api/orders', { signal })).data

export const getOrder = async (orderId, signal) =>
  (await client.get(`/api/orders/${enc(orderId)}`, { signal })).data

export const cancelOrder = async (orderId) =>
  (await client.put(`/api/orders/${enc(orderId)}/cancel`)).data

// ---------------------------------------------------------------- thanh toan (VNPay / COD)

/** Tao hoac lay lai giao dich cua don; VNPay tra checkoutUrl. */
export const createPayment = async (orderId) =>
  (await client.post('/api/payments', { orderId })).data

export const getPaymentByOrder = async (orderId) =>
  (await client.get(`/api/payments/orders/${enc(orderId)}`)).data

/** Gui nguyen query vnp_* (bat tu WebView) de payment-service xac thuc chu ky. */
export const confirmVnpayReturn = async (search) =>
  (await client.get(`/api/payments/vnpay/return${search}`)).data

// ---------------------------------------------------------------- ho so va dia chi

export const getMyProfile = async () => (await client.get('/api/users/me')).data

export const updateMyProfile = async (changes, key) =>
  (await client.put('/api/users/me', changes, keyed(key))).data

export const changeMyPassword = async ({ currentPassword, newPassword }) =>
  (await client.put('/api/users/me/password', { currentPassword, newPassword })).data

const addressesUrl = '/api/users/me/addresses'

export const listMyAddresses = async () => (await client.get(addressesUrl)).data

export const createAddress = async (address, key) =>
  (await client.post(addressesUrl, address, keyed(key))).data

export const updateAddress = async (addressId, address, key) =>
  (await client.put(`${addressesUrl}/${enc(addressId)}`, address, keyed(key))).data

export const deleteAddress = async (addressId) => {
  await client.delete(`${addressesUrl}/${enc(addressId)}`, keyed())
}

export const setDefaultAddress = async (addressId) =>
  (await client.put(`${addressesUrl}/${enc(addressId)}/default`, null, keyed())).data

// ---------------------------------------------------------------- thong bao

export const listNotifications = async ({ page = 0, size = 20 } = {}, signal) =>
  (await client.get('/api/notifications', { params: { page, size }, signal })).data

export const markNotificationRead = async (id) =>
  (await client.patch(`/api/notifications/${enc(id)}/read`)).data

export const markAllNotificationsRead = async () =>
  (await client.patch('/api/notifications/read-all')).data
