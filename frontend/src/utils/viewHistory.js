// Ghi nho trong phien trinh duyet (sessionStorage, co the bi chan nen boc try/catch):
//  - san pham da gui su kien "view" de khong gui lap khi tai lai / quay lai trang;
//  - danh muc khach vua xem de goi y cho khach theo danh muc (FR-REC-04).
const VIEWED_LIMIT = 200
const viewedKey = (userId) => `vmarket.viewed.${userId}`
const CATEGORY_KEY = 'vmarket.lastViewedCategory'

function read(key, fallback) {
  try {
    return JSON.parse(sessionStorage.getItem(key)) ?? fallback
  } catch {
    return fallback
  }
}

function write(key, value) {
  try {
    sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Tien ich, khong bat buoc.
  }
}

/** true neu day la lan dau user xem san pham nay trong phien (va danh dau da xem). */
export function markViewed(userId, productId) {
  const viewed = read(viewedKey(userId), [])
  const list = Array.isArray(viewed) ? viewed : []
  if (list.includes(productId)) return false
  write(viewedKey(userId), [...list, productId].slice(-VIEWED_LIMIT))
  return true
}

export function forgetViewed(userId, productId) {
  const viewed = read(viewedKey(userId), [])
  if (Array.isArray(viewed))
    write(
      viewedKey(userId),
      viewed.filter((id) => id !== productId),
    )
}

export const rememberViewedCategory = (categoryId) => categoryId && write(CATEGORY_KEY, categoryId)

export function lastViewedCategory() {
  const value = read(CATEGORY_KEY, null)
  return typeof value === 'string' ? value : null
}
