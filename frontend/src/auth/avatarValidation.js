const avatarUrlPattern = /^https?:\/\/\S+$/

function parses(value) {
  try {
    new URL(value)
    return true
  } catch {
    return false
  }
}

// Mirrors UpdateProfileRequest.avatarUrl: empty, or an http(s) URL of at most 500 characters.
export function validateAvatarUrl({ avatarUrl }) {
  const value = avatarUrl?.trim() || ''
  if (!value) return {}
  if (value.length > 500) return { avatarUrl: 'Đường dẫn ảnh tối đa 500 ký tự.' }
  if (!avatarUrlPattern.test(value) || !parses(value))
    return { avatarUrl: 'Đường dẫn ảnh phải bắt đầu bằng http:// hoặc https://.' }
  return {}
}
