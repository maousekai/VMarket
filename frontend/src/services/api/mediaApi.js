import apiClient from './apiClient'

// Upload endpoints return { url } and do NOT write it to the profile: the caller
// persists that URL when the user presses Save. Keeping the two steps apart lets
// the form cancel cleanly and lets us remove the orphan object if saving fails.
const UPLOAD_TIMEOUT = 45_000

async function upload(url, file) {
  const body = new FormData()
  body.append('file', file)
  // No Content-Type override: the browser must add the multipart boundary itself.
  return (await apiClient.post(url, body, { timeout: UPLOAD_TIMEOUT })).data
}

export const uploadMyAvatar = (file) => upload('/api/users/me/avatar', file)

export const uploadMyShopLogo = (file) => upload('/api/shops/me/logo', file)

export const uploadMyShopCover = (file) => upload('/api/shops/me/cover', file)

// Best-effort cleanup of an image uploaded during a save that failed afterwards.
// The server ignores URLs outside the current user's own folder, so this is safe
// even if the URL came from somewhere else.
export const deleteMyMedia = async (url) => {
  await apiClient.delete('/api/users/me/media', { params: { url } })
}

export const deleteMyShopMedia = async (url) => {
  await apiClient.delete('/api/shops/me/media', { params: { url } })
}

export async function discardUploaded(urls, remove) {
  await Promise.allSettled(urls.filter(Boolean).map((url) => remove(url)))
}
