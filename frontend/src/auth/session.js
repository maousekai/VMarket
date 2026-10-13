import * as authApi from '../services/api/authApi'
import * as userApi from '../services/api/userApi'
import { bindSessionManager, getApiError } from '../services/api/apiClient'

const terminalCodes = new Set([
  'REFRESH_TOKEN_MISSING',
  'INVALID_REFRESH_TOKEN',
  'REFRESH_TOKEN_EXPIRED',
  'REFRESH_TOKEN_REUSED',
  'SESSION_NOT_FOUND',
  'ACCOUNT_LOCKED',
  'ACCOUNT_SUSPENDED',
  'ACCOUNT_DISABLED',
])

function clientError(code, message) {
  return Object.assign(new Error(message), { code, status: 409 })
}

function claimsFromToken(token) {
  try {
    const value = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(
      new TextDecoder().decode(Uint8Array.from(atob(value), (c) => c.charCodeAt(0))),
    )
  } catch {
    throw clientError(
      'INVALID_TOKEN_RESPONSE',
      'Máy chủ trả về phiên không hợp lệ. Vui lòng thử lại.',
    )
  }
}

export function sessionFromResponse(response, now = Date.now()) {
  const claims = claimsFromToken(response.accessToken || '')
  if (!claims.sub || !claims.email || !Number.isFinite(Number(response.expiresIn)))
    throw clientError(
      'INVALID_TOKEN_RESPONSE',
      'Máy chủ trả về phiên không hợp lệ. Vui lòng thử lại.',
    )
  const expiresAt = Math.min(
    now + Number(response.expiresIn) * 1000,
    (claims.exp || Infinity) * 1000,
  )
  return {
    accessToken: response.accessToken,
    expiresAt,
    refreshAt: expiresAt - Math.min(30000, Math.max(1000, (expiresAt - now) / 10)),
    userId: claims.sub,
    email: claims.email,
    username: claims.username || '',
    status: response.status,
    roles: response.roles || [],
  }
}

const sameEmail = (a, b) => a?.trim().toLowerCase() === b?.trim().toLowerCase()
const revisionKey = 'vmarket-auth-revision'

function browserRevisionStorage() {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

const initialSnapshot = () => ({
  session: null,
  bootstrapState: 'loading',
  error: null,
  userProfile: null,
  profileState: 'idle',
  profileError: null,
  registrationDraft: null,
})

// Dependencies are injectable so concurrency and failure handling can be tested without a server.
export function createSessionManager({
  api = authApi,
  profiles = userApi,
  now = () => Date.now(),
  locks = globalThis.navigator?.locks,
  revisionStorage = browserRevisionStorage(),
  channelFactory = () =>
    typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined'
      ? new BroadcastChannel('vmarket-auth')
      : null,
  eventTarget = typeof window !== 'undefined' ? window : null,
  documentTarget = typeof document !== 'undefined' ? document : null,
  delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  makeKey = () =>
    globalThis.crypto.randomUUID?.() ||
    Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (byte) =>
      byte.toString(16).padStart(2, '0'),
    ).join(''),
} = {}) {
  let snapshot = initialSnapshot()
  let generation = 0
  let refreshFlight
  let logoutFlight
  let authFlight
  let profileFlight
  let saveFlight
  let pendingSave
  let timer
  let channel
  let started = false
  let lastRevision = 0
  let reconcileFlight
  let cookieQueue = Promise.resolve()
  const subscribers = new Set()

  const update = (changes) => {
    snapshot = { ...snapshot, ...changes }
    subscribers.forEach((subscriber) => subscriber())
  }
  const current = (expected) => {
    if (generation !== expected)
      throw clientError('SESSION_CHANGED', 'Phiên đăng nhập đã thay đổi. Vui lòng thử lại.')
  }
  const withLock = (operation) => {
    if (locks?.request) return locks.request('vmarket-auth-cookie', operation)
    // Even without Web Locks, serialize cookie writes inside this tab.
    const next = cookieQueue.then(operation)
    cookieQueue = next.catch(() => {})
    return next
  }
  const readRevision = () => {
    try {
      if (!revisionStorage) return null
      const value = Number(revisionStorage.getItem(revisionKey) || 0)
      return Number.isSafeInteger(value) && value >= 0 ? value : 0
    } catch {
      return null
    }
  }
  // Call only while holding the cookie lock. Storage contains a counter, never tokens or PII.
  const broadcast = (type, session = null) => {
    const storedRevision = readRevision()
    if (storedRevision !== null) {
      try {
        const revision = Math.max(storedRevision, lastRevision) + 1
        revisionStorage.setItem(revisionKey, String(revision))
        lastRevision = revision
        channel?.postMessage({ type, session, revision })
        return
      } catch {
        // Private browsing/storage failures fall back to reading the authoritative server cookie.
      }
    }
    channel?.postMessage({ type })
  }
  const clearSession = (notify = true, cookieLockHeld = false) => {
    generation += 1
    clearTimeout(timer)
    pendingSave = null
    update({
      session: null,
      bootstrapState: 'ready',
      error: null,
      userProfile: null,
      profileState: 'idle',
      profileError: null,
      registrationDraft: null,
    })
    if (!notify) return
    if (cookieLockHeld) {
      broadcast('logout')
    } else {
      const expected = generation
      const previousRevision = lastRevision
      withLock(() => {
        if (generation !== expected || snapshot.session) return
        // A newer cookie mutation wins over an invalidation from an older access-token request.
        const latestRevision = readRevision()
        if (latestRevision !== null && latestRevision > previousRevision) return
        broadcast('logout')
      }).catch(() => {})
    }
  }

  const scheduleRefresh = (retry = false) => {
    clearTimeout(timer)
    if (!started || !snapshot.session) return
    const wait = retry ? 15000 : Math.max(1000, snapshot.session.refreshAt - now())
    timer = setTimeout(() => {
      refresh().catch(() => {})
    }, wait)
  }

  const applySession = (session, notify = true) => {
    const identityChanged = session.userId !== snapshot.session?.userId
    if (identityChanged) {
      generation += 1
      pendingSave = null
    }
    let draft = snapshot.registrationDraft
    if (draft && !sameEmail(draft.email, session.email)) draft = null
    if (draft) draft = { ...draft, userId: session.userId }
    update({
      session,
      bootstrapState: 'ready',
      error: null,
      registrationDraft: draft,
      ...(identityChanged ? { userProfile: null, profileState: 'idle', profileError: null } : {}),
    })
    scheduleRefresh()
    if (notify) broadcast('session', session)
    return session
  }

  function refresh() {
    if (refreshFlight) return refreshFlight
    const expected = generation
    const operation = withLock(async () => {
      current(expected)
      for (let attempt = 0; attempt < 2; attempt += 1) {
        try {
          const response = await api.refreshSession()
          current(expected)
          return applySession(sessionFromResponse(response, now()))
        } catch (error) {
          current(expected)
          const detail = getApiError(error)
          if (detail.code === 'REFRESH_TOKEN_ROTATION_CONFLICT' && attempt === 0) {
            await delay(150)
            current(expected)
            continue
          }
          if (terminalCodes.has(detail.code)) {
            clearSession(true, true)
          } else {
            update({
              error: detail,
              bootstrapState: snapshot.session ? 'ready' : 'error',
            })
            scheduleRefresh(true)
          }
          throw error
        }
      }
    })
    refreshFlight = Promise.resolve(operation).finally(() => {
      refreshFlight = undefined
    })
    return refreshFlight
  }

  function authenticate(method, values) {
    if (authFlight) return authFlight
    generation += 1
    const expected = generation
    update({ error: null })
    const operation = withLock(async () => {
      current(expected)
      try {
        const response = await api[method](values)
        current(expected)
        return applySession(sessionFromResponse(response, now()))
      } catch (error) {
        if (generation === expected) {
          update({ error: getApiError(error), bootstrapState: 'ready' })
          recoverInterruptedProfile()
        }
        throw error
      }
    })
    authFlight = Promise.resolve(operation).finally(() => {
      authFlight = undefined
    })
    return authFlight
  }

  function logout() {
    if (logoutFlight) return logoutFlight
    // Invalidate in-flight responses immediately; clear UI only when the server confirms logout.
    generation += 1
    const expected = generation
    clearTimeout(timer)
    const operation = withLock(async () => {
      current(expected)
      try {
        await api.logoutAccount()
        current(expected)
        clearSession(true, true)
      } catch (error) {
        if (generation === expected) {
          update({
            error: getApiError(error),
            bootstrapState: snapshot.session ? 'ready' : 'error',
          })
          recoverInterruptedProfile()
          scheduleRefresh(true)
        }
        throw error
      }
    })
    logoutFlight = Promise.resolve(operation).finally(() => {
      logoutFlight = undefined
    })
    return logoutFlight
  }

  function requireActiveSession() {
    if (!snapshot.session || snapshot.session.status !== 'ACTIVE')
      throw clientError('SESSION_REQUIRED', 'Vui lòng đăng nhập và xác thực email trước.')
    return snapshot.session
  }

  function recoverInterruptedProfile() {
    if (snapshot.profileState !== 'loading') return
    if (snapshot.userProfile) {
      update({ profileState: 'ready' })
    } else if (snapshot.session?.status === 'ACTIVE') {
      loadProfile().catch(() => {})
    } else {
      update({ profileState: 'idle' })
    }
  }

  function loadProfile() {
    const session = requireActiveSession()
    if (profileFlight?.userId === session.userId && profileFlight.generation === generation)
      return profileFlight.promise
    const expected = generation
    update({ profileState: 'loading', profileError: null })
    const promise = profiles
      .getMyProfile()
      .then((profile) => {
        current(expected)
        if (profile.userId !== session.userId)
          throw clientError('PROFILE_IDENTITY_MISMATCH', 'Không thể tải hồ sơ của phiên hiện tại.')
        update({ userProfile: profile, profileState: 'ready', profileError: null })
        return profile
      })
      .catch((error) => {
        if (generation === expected)
          update({ profileState: 'error', profileError: getApiError(error) })
        throw error
      })
      .finally(() => {
        if (profileFlight?.promise === promise) profileFlight = undefined
      })
    profileFlight = { userId: session.userId, generation: expected, promise }
    return promise
  }

  function saveProfile(changes) {
    const session = requireActiveSession()
    if (saveFlight?.userId === session.userId && saveFlight.generation === generation)
      return saveFlight.promise
    const expected = generation
    const signature = JSON.stringify({ fullName: changes.fullName, phone: changes.phone })
    const promise = (async () => {
      try {
        if (pendingSave?.userId !== session.userId || pendingSave.signature !== signature) {
          const profile = snapshot.userProfile || (await loadProfile())
          current(expected)
          pendingSave = {
            userId: session.userId,
            signature,
            key: makeKey(),
            body: userApi.profileUpdateBody(profile, changes),
          }
        }
        current(expected)
        update({ profileState: 'loading', profileError: null })
        const profile = await profiles.updateMyProfile(pendingSave.body, pendingSave.key)
        current(expected)
        if (profile.userId !== session.userId)
          throw clientError('PROFILE_IDENTITY_MISMATCH', 'Không thể lưu hồ sơ của phiên hiện tại.')
        pendingSave = null
        update({ userProfile: profile, profileState: 'ready', profileError: null })
        return profile
      } catch (error) {
        if (generation === expected)
          update({ profileState: 'error', profileError: getApiError(error) })
        throw error
      }
    })().finally(() => {
      if (saveFlight?.promise === promise) saveFlight = undefined
    })
    saveFlight = { userId: session.userId, generation: expected, promise }
    return promise
  }

  function setRegistrationDraft({ email, fullName, phone }) {
    // Explicit allowlist prevents passwords, OTPs, or other registration values entering state.
    update({
      registrationDraft: {
        email: email.trim(),
        fullName: fullName?.trim() || '',
        phone: phone?.trim() || '',
      },
    })
  }

  async function saveRegistrationProfile() {
    const session = requireActiveSession()
    const draft = snapshot.registrationDraft
    if (!draft) return snapshot.userProfile || loadProfile()
    if (!sameEmail(draft.email, session.email) || (draft.userId && draft.userId !== session.userId))
      throw clientError(
        'PROFILE_IDENTITY_MISMATCH',
        'Thông tin đăng ký không thuộc phiên hiện tại.',
      )
    const profile = await saveProfile(draft)
    if (snapshot.registrationDraft === draft) update({ registrationDraft: null })
    return profile
  }

  function reconcileCookie() {
    if (reconcileFlight?.generation === generation) return reconcileFlight.promise
    const expected = generation
    const promise = withLock(async () => {
      current(expected)
      try {
        const response = await api.refreshSession()
        current(expected)
        return applySession(sessionFromResponse(response, now()), false)
      } catch (error) {
        current(expected)
        const detail = getApiError(error)
        if (terminalCodes.has(detail.code)) clearSession(false)
        else {
          update({ error: detail, bootstrapState: snapshot.session ? 'ready' : 'error' })
          scheduleRefresh(true)
        }
        throw error
      }
    }).finally(() => {
      if (reconcileFlight?.promise === promise) reconcileFlight = undefined
    })
    reconcileFlight = { generation: expected, promise }
    return promise
  }

  const onMessage = ({ data }) => {
    if (!data || !['logout', 'session'].includes(data.type)) return
    const latestRevision = readRevision()
    if (!Number.isSafeInteger(data.revision) || data.revision < 1 || latestRevision === null) {
      if (data.type === 'logout') clearSession(false)
      // Never restore a JWT from an unordered message: an older queued message may name another user.
      reconcileCookie().catch(() => {})
      return
    }
    if (data.revision <= lastRevision || latestRevision > data.revision) return
    lastRevision = data.revision
    if (data.type === 'logout') {
      clearSession(false)
    } else if (data.type === 'session' && data.session?.accessToken) {
      try {
        const next = sessionFromResponse(
          {
            ...data.session,
            expiresIn: Math.max(0, (data.session.expiresAt - now()) / 1000),
          },
          now(),
        )
        if (next.expiresAt <= now()) return
        // Cookie identity changes affect every tab, even if another operation is in flight.
        applySession(next, false)
      } catch {
        // Ignore malformed messages; the next refresh will restore from the server cookie.
      }
    }
  }
  const onFocus = () => {
    if (documentTarget?.visibilityState === 'hidden') return
    if (snapshot.session && snapshot.session.refreshAt <= now()) refresh().catch(() => {})
  }

  function start() {
    if (started) return stop
    started = true
    channel = channelFactory()
    if (channel) channel.onmessage = onMessage
    eventTarget?.addEventListener('focus', onFocus)
    documentTarget?.addEventListener('visibilitychange', onFocus)
    if (snapshot.bootstrapState !== 'ready') refresh().catch(() => {})
    else scheduleRefresh()
    return stop
  }

  function stop() {
    started = false
    clearTimeout(timer)
    channel?.close()
    channel = null
    eventTarget?.removeEventListener('focus', onFocus)
    documentTarget?.removeEventListener('visibilitychange', onFocus)
  }

  return {
    getSnapshot: () => snapshot,
    getGeneration: () => generation,
    subscribe: (listener) => {
      subscribers.add(listener)
      return () => subscribers.delete(listener)
    },
    start,
    stop,
    refresh,
    logout,
    login: (values) =>
      authenticate('loginAccount', {
        identifier: values.identifier.trim(),
        password: values.password,
      }),
    verifyOtp: (values) =>
      authenticate('verifyOtp', { email: values.email.trim(), otp: values.otp }),
    loadProfile,
    saveProfile,
    setRegistrationDraft,
    clearRegistrationDraft: () => update({ registrationDraft: null }),
    invalidateSession: () => clearSession(),
    saveRegistrationProfile,
    clearError: () => update({ error: null }),
  }
}

export const sessionManager = createSessionManager()
bindSessionManager(sessionManager)
