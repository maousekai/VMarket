import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useSyncExternalStore,
  type ReactNode,
} from 'react'
import { usePathname, useRouter, type Href } from 'expo-router'
import { AppState } from 'react-native'

import * as authApi from '@/api/auth'
import { refreshSession } from '@/api/client'
import { AuthStore, type AuthUser } from '@/auth/session'
import { registerDeviceToken, unregisterDeviceToken } from '@/lib/notifications'
import { queryClient } from '@/lib/queryClient'
import { safeReturnTo } from '@/lib/safeReturnTo'

export interface AuthContextValue {
  status: 'loading' | 'guest' | 'authenticated'
  user: AuthUser | null
  isShipper: boolean
  /** Signed in but email not verified yet -> must go to /(auth)/verify-email. */
  isPending: boolean
  login: (email: string, password: string) => Promise<AuthUser>
  /** Creates the account only (status PENDING, NOT logged in). Then call requestOtp + verifyOtp. */
  registerAccount: (input: authApi.RegisterRequest) => Promise<authApi.RegisterResponse>
  requestOtp: (email: string) => Promise<authApi.OtpRequestResponse>
  /** Verifies the code and signs the user in. */
  verifyOtp: (email: string, otp: string) => Promise<AuthUser>
  logout: () => Promise<void>
  /** Force a refresh now. Resolves true when a session is active afterwards. */
  refresh: () => Promise<boolean>
}

const AuthContext = createContext<AuthContextValue | null>(null)

const REFRESH_LEAD_MS = 30_000
/** Waits between retries after a non-terminal (offline / 5xx) refresh failure. */
const REFRESH_RETRY_MS = [5_000, 15_000, 60_000]

export function AuthProvider({ children }: { children: ReactNode }) {
  const snap = useSyncExternalStore(
    AuthStore.subscribe,
    AuthStore.getSnapshot,
    AuthStore.getSnapshot,
  )
  const router = useRouter()
  const pathname = usePathname()

  // A signed-in session died (terminal refresh failure): show the "session expired" screen once.
  useEffect(() => {
    if (!snap.sessionExpired) return
    AuthStore.acknowledgeSessionExpired()
    // Never carry an (auth) path as returnTo: after login it would redirect back into the auth stack.
    const returnTo = safeReturnTo(pathname)
    router.replace({
      pathname: '/(auth)/session-expired',
      params: returnTo ? { returnTo } : {},
    } as unknown as Href)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snap.sessionExpired])

  // Never show one account's cached data to the next account on the same device.
  const lastUserId = useRef<string | null>(null)
  const userId = snap.user?.userId ?? null
  useEffect(() => {
    if (lastUserId.current && lastUserId.current !== userId) void queryClient.resetQueries()
    lastUserId.current = userId
  }, [userId])

  // Restore session on app start.
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      await AuthStore.loadPersisted()
      if (!cancelled) await refreshSession()
      if (!cancelled) AuthStore.setGuest()
    })()
    return () => {
      cancelled = true
    }
  }, [])

  // Retry restoring when coming back to the foreground while offline-guest.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s !== 'active') return
      const cur = AuthStore.getSnapshot()
      if (cur.phase === 'guest' && AuthStore.getRefreshToken()) void refreshSession()
      else if (cur.phase === 'authenticated' && cur.expiresAt - Date.now() < REFRESH_LEAD_MS) {
        void refreshSession()
      }
    })
    return () => sub.remove()
  }, [])

  // Auto-refresh ~30s before expiry; after an offline / 5xx failure the session stays signed in,
  // so keep retrying with a short backoff (a success changes the snapshot and re-arms this effect).
  useEffect(() => {
    if (snap.phase !== 'authenticated') return
    let cancelled = false
    let attempt = 0
    const run = () => {
      void refreshSession().then((ok) => {
        if (cancelled || ok || AuthStore.getSnapshot().phase !== 'authenticated') return
        const wait = REFRESH_RETRY_MS[Math.min(attempt, REFRESH_RETRY_MS.length - 1)]
        attempt += 1
        timer = setTimeout(run, wait)
      })
    }
    const delay = Math.max(1000, snap.expiresAt - Date.now() - REFRESH_LEAD_MS)
    let timer = setTimeout(run, delay)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [snap.phase, snap.accessToken, snap.expiresAt])

  // Bind this device's push token to every authenticated ACTIVE session, restored ones included.
  // Unbinding happens in AuthStore.clear() (see @/lib/notifications), which covers every sign-out.
  const accountStatus = snap.user?.status
  useEffect(() => {
    if (snap.phase === 'authenticated' && accountStatus === 'ACTIVE') void registerDeviceToken()
  }, [snap.phase, userId, accountStatus])

  const afterSignIn = useCallback((user: AuthUser) => user, [])

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await authApi.login(email.trim(), password)
      return afterSignIn(AuthStore.apply(res))
    },
    [afterSignIn],
  )

  const verifyOtp = useCallback(
    async (email: string, otp: string) => {
      const res = await authApi.verifyOtp(email.trim(), otp)
      return afterSignIn(AuthStore.apply(res))
    },
    [afterSignIn],
  )

  const logout = useCallback(async () => {
    try {
      await unregisterDeviceToken()
      await authApi.logout()
    } catch {
      // logout is idempotent server-side; always drop the local session
    }
    AuthStore.clear()
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      status: snap.phase,
      user: snap.user,
      isShipper: !!snap.user?.roles.includes('SHIPPER'),
      isPending: snap.user?.status === 'PENDING',
      login,
      registerAccount: authApi.register,
      requestOtp: authApi.requestOtp,
      verifyOtp,
      logout,
      refresh: refreshSession,
    }),
    [snap.phase, snap.user, login, verifyOtp, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
