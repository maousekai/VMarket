import { decodeJwt } from '@/lib/jwt'
import { secureStorage } from '@/lib/storage'

export type AccountStatus = 'PENDING' | 'ACTIVE'
export type AuthPhase = 'loading' | 'guest' | 'authenticated'

export interface TokenResponse {
  accessToken: string
  tokenType?: string
  /** seconds */
  expiresIn: number
  status: AccountStatus
  roles: string[]
  /** Only present for `X-Client-Type: mobile` requests without Origin (native). */
  refreshToken?: string
}

export interface AuthUser {
  userId: string
  email: string
  username: string
  roles: string[]
  status: AccountStatus
  emailVerified: boolean
}

export interface AuthSnapshot {
  phase: AuthPhase
  user: AuthUser | null
  accessToken: string | null
  /** epoch ms when the access token stops being valid */
  expiresAt: number
  /** True when a signed-in session was invalidated by a terminal refresh failure (not a logout). */
  sessionExpired: boolean
}

const REFRESH_KEY = 'vmarket.refreshToken'

export function userFromTokenResponse(res: TokenResponse): {
  user: AuthUser
  expiresAt: number
} {
  const claims = decodeJwt(res.accessToken) ?? {}
  const now = Date.now()
  const byTtl = now + (res.expiresIn ?? 0) * 1000
  const expiresAt = claims.exp ? Math.min(byTtl, claims.exp * 1000) : byTtl
  return {
    expiresAt,
    user: {
      userId: claims.sub ?? '',
      email: claims.email ?? '',
      username: claims.username ?? '',
      roles: res.roles ?? claims.roles ?? [],
      status: res.status,
      emailVerified: claims.email_verified ?? res.status === 'ACTIVE',
    },
  }
}

type Listener = () => void
/** Runs inside `clear()` while the old session is still known; must not throw or block. */
type ClearHook = (accessToken: string | null, reason?: 'expired') => void

/**
 * In-memory auth state + persisted refresh token. Framework-agnostic singleton so that the
 * axios client (non-React) and AuthProvider (React) share one source of truth.
 * UI code should use `useAuth()`, not this directly.
 */
class AuthStoreImpl {
  private snapshot: AuthSnapshot = {
    phase: 'loading',
    user: null,
    accessToken: null,
    expiresAt: 0,
    sessionExpired: false,
  }
  private listeners = new Set<Listener>()
  private refreshToken: string | null = null
  private generation = 0
  private beforeClear = new Set<ClearHook>()

  subscribe = (l: Listener) => {
    this.listeners.add(l)
    return () => {
      this.listeners.delete(l)
    }
  }

  getSnapshot = () => this.snapshot

  getAccessToken() {
    return this.snapshot.accessToken
  }

  getRefreshToken() {
    return this.refreshToken
  }

  /** Increments whenever the signed-in identity changes or the session is cleared. */
  getGeneration() {
    return this.generation
  }

  private emit(next: AuthSnapshot) {
    this.snapshot = next
    this.listeners.forEach((l) => l())
  }

  /** Load the persisted refresh token (native). Does not change phase. */
  async loadPersisted() {
    try {
      this.refreshToken = await secureStorage.getItem(REFRESH_KEY)
    } catch {
      this.refreshToken = null
    }
  }

  /** Apply a login / otp-verify / refresh response. */
  apply(res: TokenResponse) {
    const { user, expiresAt } = userFromTokenResponse(res)
    if (this.snapshot.user?.userId !== user.userId) this.generation += 1
    if (res.refreshToken) {
      this.refreshToken = res.refreshToken
      secureStorage.setItem(REFRESH_KEY, res.refreshToken).catch(() => {})
    }
    this.emit({
      phase: 'authenticated',
      user,
      accessToken: res.accessToken,
      expiresAt,
      sessionExpired: false,
    })
    return user
  }

  /** Register a hook run by `clear()` for a signed-in session (push unregister, user data wipe). */
  addBeforeClear(fn: ClearHook) {
    this.beforeClear.add(fn)
    return () => {
      this.beforeClear.delete(fn)
    }
  }

  /**
   * Drop the session (logout, terminal refresh failure, suspension). Pass `expired` when a
   * signed-in session was invalidated, so the UI can show the "session expired" screen.
   */
  clear(reason?: 'expired') {
    const wasSignedIn = this.snapshot.phase === 'authenticated'
    if (wasSignedIn) {
      this.beforeClear.forEach((fn) => {
        try {
          fn(this.snapshot.accessToken, reason)
        } catch {
          // best effort
        }
      })
    }
    this.generation += 1
    this.refreshToken = null
    secureStorage.removeItem(REFRESH_KEY).catch(() => {})
    this.emit({
      phase: 'guest',
      user: null,
      accessToken: null,
      expiresAt: 0,
      sessionExpired: reason === 'expired' && wasSignedIn,
    })
  }

  /** Called once the "session expired" screen has been presented. */
  acknowledgeSessionExpired() {
    if (this.snapshot.sessionExpired) this.emit({ ...this.snapshot, sessionExpired: false })
  }

  /** Mark restore finished without a session, keeping any stored refresh token (offline start). */
  setGuest() {
    if (this.snapshot.phase === 'authenticated') return
    this.emit({ ...this.snapshot, phase: 'guest', user: null, accessToken: null, expiresAt: 0 })
  }
}

export const AuthStore = new AuthStoreImpl()
