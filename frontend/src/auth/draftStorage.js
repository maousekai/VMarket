export const registrationDraftKey = 'vmarket-registration-draft'
export const registrationDraftTtl = 30 * 60 * 1000

export const draftStorageWarning =
  'Trình duyệt không cho lưu thông tin hồ sơ tạm. Nếu tải lại trang, bạn cần nhập lại họ tên và số điện thoại tại Hồ sơ cá nhân.'
export const draftExpiredWarning =
  'Thông tin hồ sơ tạm đã hết hạn. Bạn có thể hoàn thiện họ tên và số điện thoại tại Hồ sơ cá nhân sau khi xác thực email.'
const draftInvalidWarning =
  'Không thể khôi phục thông tin hồ sơ tạm. Bạn có thể nhập lại họ tên và số điện thoại tại Hồ sơ cá nhân sau khi xác thực email.'

export function browserDraftStorage() {
  try {
    return typeof window !== 'undefined' ? window.sessionStorage : null
  } catch {
    return null
  }
}

export function removeStoredDraft(storage) {
  try {
    if (!storage) return draftStorageWarning
    storage.removeItem(registrationDraftKey)
    return null
  } catch {
    return draftStorageWarning
  }
}

export function storeDraft(storage, draft, savedAt) {
  try {
    if (!storage) return draftStorageWarning
    // Never persist the session binding, password, OTP, or tokens passed alongside a draft.
    storage.setItem(
      registrationDraftKey,
      JSON.stringify({ email: draft.email, fullName: draft.fullName, phone: draft.phone, savedAt }),
    )
    return null
  } catch {
    return draftStorageWarning
  }
}

export function restoreDraft(storage, now) {
  const empty = { draft: null, savedAt: null, warning: null }
  let value
  try {
    if (!storage) return { ...empty, warning: draftStorageWarning }
    const raw = storage.getItem(registrationDraftKey)
    if (raw === null) return empty
    value = JSON.parse(raw)
  } catch (error) {
    if (!(error instanceof SyntaxError)) return { ...empty, warning: draftStorageWarning }
    removeStoredDraft(storage)
    return { ...empty, warning: draftInvalidWarning }
  }
  const valid =
    value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.keys(value).length === 4 &&
    typeof value.email === 'string' &&
    value.email.length <= 320 &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.email) &&
    typeof value.fullName === 'string' &&
    value.fullName.length <= 100 &&
    typeof value.phone === 'string' &&
    (!value.phone || /^(0\d{9}|\+84\d{9})$/.test(value.phone)) &&
    Number.isSafeInteger(value.savedAt) &&
    value.savedAt >= 0 &&
    value.savedAt <= now
  if (!valid || now - value.savedAt >= registrationDraftTtl) {
    removeStoredDraft(storage)
    return { ...empty, warning: valid ? draftExpiredWarning : draftInvalidWarning }
  }
  return {
    draft: { email: value.email, fullName: value.fullName, phone: value.phone },
    savedAt: value.savedAt,
    warning: null,
  }
}
