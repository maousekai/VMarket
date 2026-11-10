import { api, newIdempotencyKey } from '@/api/client'
import { secureStorage } from '@/lib/storage'

const KEY = 'vmarket.registrationDraft'
const TTL_MS = 30 * 60 * 1000

/** Profile data typed at sign-up; it can only be saved once the account is verified and signed in. */
export interface RegistrationDraft {
  email: string
  fullName: string
  phone: string
}

interface StoredDraft extends RegistrationDraft {
  savedAt: number
}

export async function saveDraft(draft: RegistrationDraft): Promise<void> {
  try {
    const stored: StoredDraft = { ...draft, email: draft.email.toLowerCase(), savedAt: Date.now() }
    await secureStorage.setItem(KEY, JSON.stringify(stored))
  } catch {
    // The draft is a convenience: the user can fill the profile in later.
  }
}

export async function clearDraft(): Promise<void> {
  try {
    await secureStorage.removeItem(KEY)
  } catch {
    // nothing to clean up
  }
}

async function readDraft(email: string): Promise<StoredDraft | null> {
  try {
    const raw = await secureStorage.getItem(KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<StoredDraft>
    const valid =
      typeof value.email === 'string' &&
      typeof value.fullName === 'string' &&
      typeof value.phone === 'string' &&
      typeof value.savedAt === 'number' &&
      Date.now() - value.savedAt < TTL_MS
    if (!valid) {
      await clearDraft()
      return null
    }
    return value.email === email.trim().toLowerCase() ? (value as StoredDraft) : null
  } catch {
    await clearDraft()
    return null
  }
}

interface Profile {
  fullName: string | null
  avatarUrl: string | null
  phone: string | null
  dateOfBirth: string | null
  gender: string | null
}

/**
 * Sends the draft's fullName/phone to `PUT /api/users/me` (a full-replace endpoint, so the current
 * profile is merged first) after the first successful verified login, then drops the draft.
 * Returns false when a draft existed but could not be saved (it is kept for the next login).
 */
export async function flushRegistrationDraft(email: string): Promise<boolean> {
  const draft = await readDraft(email)
  if (!draft) return true
  try {
    const current = (await api.get<Profile>('/api/users/me')).data
    await api.put(
      '/api/users/me',
      {
        fullName: draft.fullName || current.fullName,
        phone: draft.phone || current.phone,
        avatarUrl: current.avatarUrl,
        dateOfBirth: current.dateOfBirth,
        gender: current.gender,
      },
      { headers: { 'Idempotency-Key': newIdempotencyKey() } },
    )
    await clearDraft()
    return true
  } catch {
    return false
  }
}
