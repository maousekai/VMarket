import type { ImagePickerAsset } from 'expo-image-picker'

export const AVATAR_MAX_BYTES = 5 * 1024 * 1024
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const EXTENSION_TYPES: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
}

function mimeTypeOf(asset: ImagePickerAsset): string | undefined {
  if (asset.mimeType) return asset.mimeType.toLowerCase()
  const name = asset.fileName ?? asset.uri.split('?')[0]
  return EXTENSION_TYPES[name.split('.').pop()?.toLowerCase() ?? '']
}

/** Returns a Vietnamese reason when the picked file cannot be used, otherwise null. */
export function avatarFileError(asset: ImagePickerAsset): string | null {
  const type = mimeTypeOf(asset)
  if (!type || !ALLOWED_TYPES.includes(type)) return 'Chỉ hỗ trợ ảnh JPG, PNG hoặc WebP.'
  if (asset.fileSize && asset.fileSize > AVATAR_MAX_BYTES)
    return 'Ảnh quá lớn. Chọn ảnh tối đa 5 MB.'
  return null
}

/** Thrown while the backend has no media upload endpoint (api-auth-user.md, gap G2). */
export class AvatarUploadUnavailableError extends Error {
  constructor() {
    super('Máy chủ chưa hỗ trợ tải ảnh đại diện lên.')
  }
}

/**
 * Uploads the picked image and returns its public http(s) URL for `PUT /api/users/me`.
 * user-service only stores an `avatarUrl` string and no service accepts file uploads, so there is
 * no legitimate URL to send (a base64 data URI would break the 500-character limit). Replace the
 * body of this function with `POST /api/users/me/avatar` once that endpoint exists.
 */
export async function uploadAvatar(asset: ImagePickerAsset): Promise<string> {
  void asset
  throw new AvatarUploadUnavailableError()
}
