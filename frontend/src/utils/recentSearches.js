// "Tim kiem gan day" (Figma UC-03): luu tren trinh duyet, rieng tung tai khoan / khach.
// localStorage co the bi chan (che do rieng tu) nen moi lan doc/ghi deu boc try/catch.
const MAX_RECENT = 5
const storageKey = (userId) => `vmarket.recentSearches.${userId || 'guest'}`

export function readRecentSearches(userId) {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey(userId)) || '[]')
    return Array.isArray(value)
      ? value.filter((item) => typeof item === 'string').slice(0, MAX_RECENT)
      : []
  } catch {
    return []
  }
}

function write(userId, items) {
  try {
    if (items.length) localStorage.setItem(storageKey(userId), JSON.stringify(items))
    else localStorage.removeItem(storageKey(userId))
  } catch {
    // Khong luu duoc thi bo qua: day chi la tien ich.
  }
  return items
}

/** Dua tu khoa len dau, bo trung lap khong phan biet hoa thuong. */
export function addRecentSearch(userId, keyword) {
  const value = keyword.trim()
  if (!value) return readRecentSearches(userId)
  const lower = value.toLocaleLowerCase('vi')
  const rest = readRecentSearches(userId).filter((item) => item.toLocaleLowerCase('vi') !== lower)
  return write(userId, [value, ...rest].slice(0, MAX_RECENT))
}

export function removeRecentSearch(userId, keyword) {
  return write(
    userId,
    readRecentSearches(userId).filter((item) => item !== keyword),
  )
}
