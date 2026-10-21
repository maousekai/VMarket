import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSessionManager } from './session'
import { registrationDraftKey, registrationDraftTtl } from './draftStorage'

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function tokenResponse(userId = 'user-1', extra = {}) {
  const claims = { sub: userId, email: `${userId}@example.com`, username: userId }
  return {
    accessToken: `header.${btoa(JSON.stringify(claims))}.signature`,
    expiresIn: 900,
    status: 'ACTIVE',
    roles: ['BUYER'],
    ...extra,
  }
}

const apiError = (code, status = 401) => ({
  response: { status, data: { error: { code, message: code } } },
})

const managers = []
function revisionStorage() {
  const values = new Map()
  return {
    getItem: vi.fn((key) => values.get(key) ?? null),
    setItem: vi.fn((key, value) => values.set(key, value)),
    removeItem: vi.fn((key) => values.delete(key)),
  }
}

function setup(options = {}) {
  const api = {
    loginAccount: vi.fn().mockResolvedValue(tokenResponse()),
    verifyOtp: vi.fn().mockResolvedValue(tokenResponse()),
    refreshSession: vi.fn().mockResolvedValue(tokenResponse()),
    logoutAccount: vi.fn().mockResolvedValue({ message: 'Đã đăng xuất' }),
    ...options.api,
  }
  const profiles = {
    getMyProfile: vi.fn().mockResolvedValue({ userId: 'user-1', fullName: 'An' }),
    updateMyProfile: vi.fn().mockImplementation(async (body) => ({ userId: 'user-1', ...body })),
    ...options.profiles,
  }
  const manager = createSessionManager({
    locks: null,
    revisionStorage: null,
    draftStorage: null,
    channelFactory: () => null,
    eventTarget: null,
    documentTarget: null,
    makeKey: () => 'stable-key',
    delay: async () => {},
    ...options,
    api,
    profiles,
  })
  managers.push(manager)
  return { api, profiles, manager }
}

afterEach(() => {
  managers.splice(0).forEach((manager) => manager.stop())
  vi.useRealTimers()
})

describe('session refresh', () => {
  it('shares one refresh across simultaneous callers', async () => {
    const request = deferred()
    const { api, manager } = setup({ api: { refreshSession: vi.fn(() => request.promise) } })
    const first = manager.refresh()
    const second = manager.refresh()
    expect(first).toBe(second)
    await Promise.resolve()
    expect(api.refreshSession).toHaveBeenCalledTimes(1)
    request.resolve(tokenResponse())
    await first
    expect(manager.getSnapshot().session.userId).toBe('user-1')
  })

  it('allows bootstrap recovery after a temporary server outage', async () => {
    const { api, manager } = setup()
    api.refreshSession.mockRejectedValueOnce(new Error('network disconnected'))
    await expect(manager.refresh()).rejects.toThrow('network disconnected')
    expect(manager.getSnapshot()).toMatchObject({ session: null, bootstrapState: 'error' })
    await manager.refresh()
    expect(manager.getSnapshot()).toMatchObject({ bootstrapState: 'ready', error: null })
  })

  it('refreshes proactively before the access token expires', async () => {
    vi.useFakeTimers()
    const { api, manager } = setup()
    manager.start()
    await manager.refresh()
    expect(api.refreshSession).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(870000)
    expect(api.refreshSession).toHaveBeenCalledTimes(2)
  })

  it('uses a bounded early margin for short-lived tokens without an immediate refresh loop', async () => {
    vi.useFakeTimers()
    const { api, manager } = setup({
      api: {
        refreshSession: vi.fn().mockResolvedValue(tokenResponse('user-1', { expiresIn: 30 })),
      },
    })
    manager.start()
    await manager.refresh()
    await vi.advanceTimersByTimeAsync(26000)
    expect(api.refreshSession).toHaveBeenCalledOnce()
    await vi.advanceTimersByTimeAsync(1000)
    expect(api.refreshSession).toHaveBeenCalledTimes(2)
  })

  it('retries a rotation conflict once and retains session if the conflict persists', async () => {
    const { api, manager } = setup()
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    api.refreshSession.mockRejectedValue(apiError('REFRESH_TOKEN_ROTATION_CONFLICT'))
    await expect(manager.refresh()).rejects.toMatchObject({ response: { status: 401 } })
    expect(api.refreshSession).toHaveBeenCalledTimes(2)
    expect(manager.getSnapshot().session.userId).toBe('user-1')
    expect(manager.getSnapshot().error.code).toBe('REFRESH_TOKEN_ROTATION_CONFLICT')
  })

  it('retains a live session on network failure and allows a later successful retry', async () => {
    const { api, manager } = setup()
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    api.refreshSession.mockRejectedValueOnce(new Error('network disconnected'))
    await expect(manager.refresh()).rejects.toThrow('network disconnected')
    expect(manager.getSnapshot().session.userId).toBe('user-1')
    expect(manager.getSnapshot().error.retryable).toBe(true)
    await manager.refresh()
    expect(manager.getSnapshot().error).toBeNull()
  })

  it('clears an expired refresh token but treats a missing bootstrap cookie as a guest', async () => {
    const { api, manager } = setup()
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    api.refreshSession.mockRejectedValueOnce(apiError('REFRESH_TOKEN_EXPIRED'))
    await expect(manager.refresh()).rejects.toMatchObject({ response: { status: 401 } })
    expect(manager.getSnapshot()).toMatchObject({ session: null, bootstrapState: 'ready' })
    api.refreshSession.mockRejectedValueOnce(apiError('REFRESH_TOKEN_MISSING'))
    await expect(manager.refresh()).rejects.toMatchObject({ response: { status: 401 } })
    expect(manager.getSnapshot()).toMatchObject({ session: null, bootstrapState: 'ready' })
  })

  it('keeps a guest bootstrap local without erasing a registration draft created in flight', async () => {
    const request = deferred()
    const channel = { postMessage: vi.fn(), close: vi.fn() }
    const storage = revisionStorage()
    const drafts = revisionStorage()
    const { manager } = setup({
      api: { refreshSession: vi.fn(() => request.promise) },
      channelFactory: () => channel,
      revisionStorage: storage,
      draftStorage: drafts,
    })
    manager.start()
    const generation = manager.getGeneration()
    const refreshing = manager.refresh()
    const rejected = expect(refreshing).rejects.toMatchObject({ response: { status: 401 } })
    manager.setRegistrationDraft({ email: 'user-1@example.com', fullName: 'An', phone: '' })
    request.reject(apiError('REFRESH_TOKEN_MISSING'))
    await rejected
    expect(manager.getSnapshot()).toMatchObject({
      session: null,
      bootstrapState: 'ready',
      registrationDraft: { email: 'user-1@example.com', fullName: 'An' },
    })
    expect(manager.getGeneration()).toBe(generation)
    expect(channel.postMessage).not.toHaveBeenCalled()
    expect(storage.setItem).not.toHaveBeenCalled()
    expect(drafts.getItem(registrationDraftKey)).toBeTruthy()
  })

  it('does not revoke a live session for a temporary password login lock', async () => {
    const channel = { postMessage: vi.fn(), close: vi.fn() }
    const { api, manager } = setup({ channelFactory: () => channel })
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    manager.start()
    api.refreshSession.mockRejectedValueOnce(apiError('ACCOUNT_LOCKED', 423))
    await expect(manager.refresh()).rejects.toMatchObject({ response: { status: 423 } })
    expect(manager.getSnapshot().session.userId).toBe('user-1')
    expect(channel.postMessage).not.toHaveBeenCalled()
  })

  it('clears and broadcasts when an authenticated session actually loses its cookie', async () => {
    const channel = { postMessage: vi.fn(), close: vi.fn() }
    const { api, manager } = setup({ channelFactory: () => channel })
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    manager.start()
    api.refreshSession.mockRejectedValueOnce(apiError('REFRESH_TOKEN_MISSING'))
    await expect(manager.refresh()).rejects.toMatchObject({ response: { status: 401 } })
    expect(manager.getSnapshot().session).toBeNull()
    expect(channel.postMessage).toHaveBeenCalledWith({ type: 'logout' })
  })

  it('ignores a refresh response that arrives after logout has started', async () => {
    const request = deferred()
    const { api, manager } = setup()
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    api.refreshSession.mockReturnValue(request.promise)
    const refreshing = manager.refresh()
    const rejected = expect(refreshing).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    await Promise.resolve()
    const loggingOut = manager.logout()
    request.resolve(tokenResponse())
    await rejected
    await loggingOut
    expect(manager.getSnapshot().session).toBeNull()
  })

  it('does not report logout success or discard session when backend logout fails', async () => {
    const { api, manager } = setup()
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    api.logoutAccount.mockRejectedValueOnce(new Error('network disconnected'))
    await expect(manager.logout()).rejects.toThrow('network disconnected')
    expect(manager.getSnapshot().session.userId).toBe('user-1')
    await manager.logout()
    expect(manager.getSnapshot().session).toBeNull()
  })

  it('recovers an interrupted profile request after logout fails', async () => {
    const request = deferred()
    const { api, manager, profiles } = setup()
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    profiles.getMyProfile.mockReturnValueOnce(request.promise)
    const loading = manager.loadProfile()
    const rejected = expect(loading).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    api.logoutAccount.mockRejectedValueOnce(new Error('network disconnected'))
    await expect(manager.logout()).rejects.toThrow('network disconnected')
    request.resolve({ userId: 'user-1', fullName: 'Stale' })
    await rejected
    await manager.loadProfile()
    expect(manager.getSnapshot()).toMatchObject({
      profileState: 'ready',
      userProfile: { fullName: 'An' },
    })
  })

  it('serializes cookie rotation and logout through the same Web Lock', async () => {
    let queue = Promise.resolve()
    const locks = {
      request: vi.fn((_, operation) => {
        const next = queue.then(operation)
        queue = next.catch(() => {})
        return next
      }),
    }
    const request = deferred()
    const { api, manager } = setup({ locks })
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    api.refreshSession.mockReturnValue(request.promise)
    const refreshing = manager.refresh()
    const rejected = expect(refreshing).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    await Promise.resolve()
    const loggingOut = manager.logout()
    expect(api.logoutAccount).not.toHaveBeenCalled()
    request.resolve(tokenResponse())
    await rejected
    await loggingOut
    expect(api.logoutAccount).toHaveBeenCalledOnce()
    expect(locks.request.mock.calls.every(([name]) => name === 'vmarket-auth-cookie')).toBe(true)
  })

  it('accepts in-memory session and logout updates from another tab', async () => {
    const channel = { postMessage: vi.fn(), close: vi.fn() }
    const storage = revisionStorage()
    const { manager } = setup({
      channelFactory: () => channel,
      revisionStorage: storage,
      api: { refreshSession: vi.fn().mockRejectedValue(apiError('REFRESH_TOKEN_MISSING')) },
    })
    manager.start()
    await Promise.resolve()
    await Promise.resolve()
    const response = tokenResponse('user-2')
    storage.setItem('vmarket-auth-revision', '2')
    channel.onmessage({
      data: {
        type: 'session',
        revision: 2,
        session: { ...response, expiresAt: Date.now() + 900000 },
      },
    })
    expect(manager.getSnapshot().session.userId).toBe('user-2')
    storage.setItem('vmarket-auth-revision', '3')
    channel.onmessage({ data: { type: 'logout', revision: 3 } })
    expect(manager.getSnapshot().session).toBeNull()
  })

  it.each(['tied timestamps', 'a clock moving backwards'])(
    'orders cross-tab logout after a session update despite %s',
    async (scenario) => {
      let clock = 10000
      let queue = Promise.resolve()
      const locks = {
        request: (_, operation) => {
          const next = queue.then(operation)
          queue = next.catch(() => {})
          return next
        },
      }
      const storage = revisionStorage()
      const firstChannel = { postMessage: vi.fn(), close: vi.fn() }
      const secondChannel = { postMessage: vi.fn(), close: vi.fn() }
      const { manager: first } = setup({
        now: () => clock,
        locks,
        revisionStorage: storage,
        channelFactory: () => firstChannel,
      })
      const { manager: second } = setup({
        now: () => clock,
        locks,
        revisionStorage: storage,
        channelFactory: () => secondChannel,
      })
      await first.login({ email: 'user-1@example.com', password: 'Secret@123' })
      await second.login({ email: 'user-1@example.com', password: 'Secret@123' })
      first.start()
      second.start()
      await first.refresh()
      const oldSession = firstChannel.postMessage.mock.lastCall[0]
      if (scenario === 'a clock moving backwards') clock = 5000
      await second.logout()
      const logout = secondChannel.postMessage.mock.lastCall[0]
      expect(logout.revision).toBeGreaterThan(oldSession.revision)
      // Even if an older session arrives before the logout notification, shared metadata is newer.
      secondChannel.onmessage({ data: oldSession })
      expect(second.getSnapshot().session).toBeNull()
      firstChannel.onmessage({ data: logout })
      expect(first.getSnapshot().session).toBeNull()
      firstChannel.onmessage({ data: oldSession })
      expect(first.getSnapshot().session).toBeNull()
      // The only persisted values are monotonic counters: no JWT, identity or draft fields.
      expect(
        storage.setItem.mock.calls.every(
          ([key, value]) => key === 'vmarket-auth-revision' && /^\d+$/.test(value),
        ),
      ).toBe(true)
    },
  )

  it('reconciles unordered session broadcasts from the server when storage is unavailable', async () => {
    const channel = { postMessage: vi.fn(), close: vi.fn() }
    const brokenStorage = {
      getItem: () => {
        throw new Error('Storage blocked')
      },
      setItem: vi.fn(),
    }
    const { manager, api } = setup({
      revisionStorage: brokenStorage,
      channelFactory: () => channel,
    })
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    manager.start()
    const seen = []
    manager.subscribe(() => seen.push(manager.getSnapshot().session?.userId || null))
    const stale = tokenResponse('old-user')
    channel.onmessage({
      data: { type: 'session', session: { ...stale, expiresAt: Date.now() + 900000 } },
    })
    await vi.waitFor(() => expect(api.refreshSession).toHaveBeenCalledOnce())
    expect(manager.getSnapshot().session.userId).toBe('user-1')
    expect(seen).not.toContain('old-user')
    expect(channel.postMessage).not.toHaveBeenCalled()
    api.refreshSession.mockRejectedValue(apiError('REFRESH_TOKEN_MISSING'))
    channel.onmessage({ data: { type: 'logout' } })
    expect(manager.getSnapshot().session).toBeNull()
    // A queued JWT cannot revive the old account after logout; only the real cookie is consulted.
    channel.onmessage({
      data: { type: 'session', session: { ...stale, expiresAt: Date.now() + 900000 } },
    })
    await vi.waitFor(() => expect(api.refreshSession).toHaveBeenCalledTimes(2))
    expect(manager.getSnapshot().session).toBeNull()
    expect(seen).not.toContain('old-user')
    expect(brokenStorage.setItem).not.toHaveBeenCalled()
  })
})

describe('profile persistence', () => {
  it('restores only the allowed profile draft after reloading without storing credentials', () => {
    const storage = revisionStorage()
    const { manager } = setup({ draftStorage: storage, now: () => 10000 })
    manager.setRegistrationDraft({
      email: ' USER-1@EXAMPLE.COM ',
      fullName: ' An ',
      phone: '0912345678',
      password: 'Secret@123',
      otp: '012345',
      accessToken: 'jwt',
      refreshToken: 'opaque',
    })
    expect(JSON.parse(storage.getItem(registrationDraftKey))).toEqual({
      email: 'user-1@example.com',
      fullName: 'An',
      phone: '0912345678',
      savedAt: 10000,
    })
    const reloaded = setup({ draftStorage: storage, now: () => 11000 }).manager
    expect(reloaded.getSnapshot()).toMatchObject({
      registrationDraft: { email: 'user-1@example.com', fullName: 'An', phone: '0912345678' },
      registrationDraftWarning: null,
    })
  })

  it.each([
    [
      'expired',
      JSON.stringify({ email: 'user-1@example.com', fullName: 'An', phone: '', savedAt: 0 }),
    ],
    [
      'future timestamp',
      JSON.stringify({
        email: 'user-1@example.com',
        fullName: 'An',
        phone: '',
        savedAt: registrationDraftTtl + 1,
      }),
    ],
    ['malformed JSON', '{'],
    ['wrong type', '[]'],
    ['invalid email', JSON.stringify({ email: 'bad', fullName: 'An', phone: '', savedAt: 1 })],
    [
      'invalid phone',
      JSON.stringify({ email: 'user-1@example.com', fullName: 'An', phone: '123', savedAt: 1 }),
    ],
    [
      'unexpected secrets',
      JSON.stringify({
        email: 'user-1@example.com',
        fullName: 'An',
        phone: '',
        savedAt: 1,
        password: 'Secret@123',
      }),
    ],
  ])('discards a %s stored draft and explains how to complete the profile', (_, stored) => {
    const storage = revisionStorage()
    storage.setItem(registrationDraftKey, stored)
    const { manager } = setup({ draftStorage: storage, now: () => registrationDraftTtl })
    expect(manager.getSnapshot().registrationDraft).toBeNull()
    expect(manager.getSnapshot().registrationDraftWarning).toMatch(/Hồ sơ cá nhân/)
    expect(storage.getItem(registrationDraftKey)).toBeNull()
  })

  it('expires an in-memory draft before saving even without reloading', async () => {
    let clock = 10000
    const storage = revisionStorage()
    const { manager, profiles } = setup({ draftStorage: storage, now: () => clock })
    manager.setRegistrationDraft({ email: 'user-1@example.com', fullName: 'An', phone: '' })
    await manager.verifyOtp({ email: 'user-1@example.com', otp: '012345' })
    clock += registrationDraftTtl
    await manager.saveRegistrationProfile()
    expect(profiles.updateMyProfile).not.toHaveBeenCalled()
    expect(manager.getSnapshot().registrationDraftWarning).toMatch(/hết hạn/)
    expect(storage.getItem(registrationDraftKey)).toBeNull()
  })

  it('expires an idle draft while the manager is running', async () => {
    vi.useFakeTimers()
    const storage = revisionStorage()
    const { manager } = setup({
      draftStorage: storage,
      api: { refreshSession: vi.fn().mockRejectedValue(apiError('REFRESH_TOKEN_MISSING')) },
    })
    manager.setRegistrationDraft({ email: 'user-1@example.com', fullName: 'An', phone: '' })
    manager.start()
    await vi.advanceTimersByTimeAsync(registrationDraftTtl)
    expect(manager.getSnapshot().registrationDraft).toBeNull()
    expect(manager.getSnapshot().registrationDraftWarning).toMatch(/hết hạn/)
    expect(storage.getItem(registrationDraftKey)).toBeNull()
  })

  it('continues with its in-memory draft when session storage is blocked', async () => {
    const blocked = {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
      removeItem: () => {
        throw new Error('blocked')
      },
    }
    const { manager, profiles } = setup({ draftStorage: blocked })
    expect(manager.getSnapshot().registrationDraftWarning).toMatch(/Trình duyệt/)
    manager.setRegistrationDraft({ email: 'user-1@example.com', fullName: 'An', phone: '' })
    await manager.verifyOtp({ email: 'user-1@example.com', otp: '012345' })
    expect(manager.getSnapshot().registrationDraft.fullName).toBe('An')
    await manager.saveRegistrationProfile()
    expect(profiles.updateMyProfile).toHaveBeenCalledOnce()
    expect(manager.getSnapshot().registrationDraft).toBeNull()
    expect(manager.getSnapshot().registrationDraftWarning).toBeNull()
  })

  it('retains the current draft after a storage write fails and clears an older stored draft', () => {
    const storage = revisionStorage()
    const { manager } = setup({ draftStorage: storage })
    manager.setRegistrationDraft({ email: 'old@example.com', fullName: 'Old', phone: '' })
    storage.setItem.mockImplementationOnce(() => {
      throw new Error('quota')
    })
    manager.setRegistrationDraft({ email: 'new@example.com', fullName: 'New', phone: '' })
    expect(manager.getSnapshot().registrationDraft.fullName).toBe('New')
    expect(manager.getSnapshot().registrationDraftWarning).toMatch(/Trình duyệt/)
    expect(storage.getItem(registrationDraftKey)).toBeNull()
  })

  it.each(['save', 'defer', 'logout', 'different account'])(
    'clears the stored profile draft after %s',
    async (action) => {
      const storage = revisionStorage()
      const { manager, api } = setup({ draftStorage: storage })
      manager.setRegistrationDraft({ email: 'user-1@example.com', fullName: 'An', phone: '' })
      if (action === 'save') {
        await manager.verifyOtp({ email: 'user-1@example.com', otp: '012345' })
        await manager.saveRegistrationProfile()
      } else if (action === 'defer') manager.clearRegistrationDraft()
      else if (action === 'logout') await manager.logout()
      else {
        api.loginAccount.mockResolvedValue(tokenResponse('user-2'))
        await manager.login({ email: 'user-2@example.com', password: 'Secret@123' })
      }
      expect(storage.getItem(registrationDraftKey)).toBeNull()
      expect(manager.getSnapshot().registrationDraft).toBeNull()
      expect(manager.getSnapshot().registrationDraftWarning).toBeNull()
    },
  )

  it('binds restored profile data to the authenticated account before saving', async () => {
    const storage = revisionStorage()
    const { manager } = setup({ draftStorage: storage })
    manager.setRegistrationDraft({ email: 'user-1@example.com', fullName: 'An', phone: '' })
    const { manager: reloaded, profiles } = setup({ draftStorage: storage })
    await reloaded.verifyOtp({ email: 'user-1@example.com', otp: '012345' })
    expect(reloaded.getSnapshot().registrationDraft.userId).toBe('user-1')
    await reloaded.saveRegistrationProfile()
    expect(profiles.updateMyProfile).toHaveBeenCalledOnce()
    expect(storage.getItem(registrationDraftKey)).toBeNull()
  })

  it('preserves fields not edited by this form and retries with the exact body and key', async () => {
    const current = {
      userId: 'user-1',
      fullName: 'Old',
      phone: '0912345678',
      avatarUrl: 'https://example.com/an.png',
      dateOfBirth: '2000-01-01',
      gender: 'OTHER',
    }
    const { manager, profiles } = setup({
      profiles: { getMyProfile: vi.fn().mockResolvedValue(current) },
    })
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    profiles.updateMyProfile.mockRejectedValueOnce(new Error('timeout'))
    const changes = { fullName: 'Nguyễn An', phone: '0987654321' }
    await expect(manager.saveProfile(changes)).rejects.toThrow('timeout')
    await manager.saveProfile(changes)
    const [first, second] = profiles.updateMyProfile.mock.calls
    expect(second).toEqual(first)
    expect(first).toEqual([
      {
        fullName: changes.fullName,
        phone: changes.phone,
        avatarUrl: current.avatarUrl,
        dateOfBirth: current.dateOfBirth,
        gender: current.gender,
      },
      'stable-key',
    ])
  })

  it('keeps only non-secret draft fields and retains them on a profile failure', async () => {
    const { manager, profiles } = setup()
    manager.setRegistrationDraft({
      email: 'user-1@example.com',
      fullName: 'An',
      phone: '0912345678',
      password: 'DoNotStore@1',
      otp: '123456',
    })
    await manager.verifyOtp({ email: 'user-1@example.com', otp: '123456' })
    expect(manager.getSnapshot().registrationDraft).toEqual({
      email: 'user-1@example.com',
      fullName: 'An',
      phone: '0912345678',
      userId: 'user-1',
    })
    profiles.updateMyProfile.mockRejectedValueOnce(new Error('timeout'))
    await expect(manager.saveRegistrationProfile()).rejects.toThrow('timeout')
    expect(manager.getSnapshot().registrationDraft.fullName).toBe('An')
    await manager.saveRegistrationProfile()
    expect(manager.getSnapshot().registrationDraft).toBeNull()
  })

  it('does not save a registration draft into a different account', async () => {
    const { api, manager, profiles } = setup()
    manager.setRegistrationDraft({ email: 'user-1@example.com', fullName: 'An', phone: '' })
    api.loginAccount.mockResolvedValue(tokenResponse('user-2'))
    await manager.login({ email: 'user-2@example.com', password: 'Secret@123' })
    expect(manager.getSnapshot().registrationDraft).toBeNull()
    expect(profiles.updateMyProfile).not.toHaveBeenCalled()
  })

  it('cannot restore a profile after logout', async () => {
    const request = deferred()
    const { manager } = setup({ profiles: { getMyProfile: vi.fn(() => request.promise) } })
    await manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    const loading = manager.loadProfile()
    const rejected = expect(loading).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    await manager.logout()
    request.resolve({ userId: 'user-1', fullName: 'Old account' })
    await rejected
    expect(manager.getSnapshot().userProfile).toBeNull()
  })
})

describe('birth date and gender editing', () => {
  const current = {
    userId: 'user-1',
    fullName: 'Old',
    phone: '0912345678',
    avatarUrl: 'https://example.com/an.png',
    dateOfBirth: '2000-01-01',
    gender: 'OTHER',
  }

  async function loggedIn(options = {}) {
    const context = setup({
      ...options,
      profiles: { getMyProfile: vi.fn().mockResolvedValue(current) },
    })
    await context.manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    return context
  }

  it('sends the edited birth date and gender and keeps the avatar', async () => {
    const { manager, profiles } = await loggedIn()
    await manager.saveProfile({
      fullName: 'An',
      phone: '',
      dateOfBirth: '2001-02-03',
      gender: 'FEMALE',
    })
    expect(profiles.updateMyProfile.mock.calls[0][0]).toEqual({
      fullName: 'An',
      phone: null,
      avatarUrl: current.avatarUrl,
      dateOfBirth: '2001-02-03',
      gender: 'FEMALE',
    })
  })

  it('clears the birth date and gender when they are emptied', async () => {
    const { manager, profiles } = await loggedIn()
    await manager.saveProfile({ fullName: 'An', phone: '', dateOfBirth: '', gender: '' })
    expect(profiles.updateMyProfile.mock.calls[0][0]).toMatchObject({
      dateOfBirth: null,
      gender: null,
    })
  })

  it('starts a new request with a new key when only the birth date changes after a failure', async () => {
    const makeKey = vi.fn().mockReturnValueOnce('key-1').mockReturnValueOnce('key-2')
    const { manager, profiles } = await loggedIn({ makeKey })
    profiles.updateMyProfile.mockRejectedValueOnce(new Error('timeout'))
    const first = { fullName: 'An', phone: '', dateOfBirth: '2001-02-03' }
    await expect(manager.saveProfile(first)).rejects.toThrow('timeout')
    await manager.saveProfile({ ...first, dateOfBirth: '2001-02-04' })
    const [attempt, retry] = profiles.updateMyProfile.mock.calls
    expect(attempt[1]).toBe('key-1')
    expect(retry[1]).toBe('key-2')
    expect(retry[0].dateOfBirth).toBe('2001-02-04')
  })
})

describe('avatar URL editing', () => {
  const current = {
    userId: 'user-1',
    fullName: 'Old',
    phone: '0912345678',
    avatarUrl: 'https://example.com/old.png',
    dateOfBirth: '2000-01-01',
    gender: 'OTHER',
  }

  async function loggedIn(options = {}) {
    const context = setup({
      ...options,
      profiles: { getMyProfile: vi.fn().mockResolvedValue(current) },
    })
    await context.manager.login({ email: 'user-1@example.com', password: 'Secret@123' })
    return context
  }

  it('sends the edited avatar URL and keeps the other stored fields', async () => {
    const { manager, profiles } = await loggedIn()
    await manager.saveProfile({
      fullName: 'An',
      phone: '0912345678',
      avatarUrl: 'https://example.com/new.png',
    })
    expect(profiles.updateMyProfile.mock.calls[0][0]).toEqual({
      fullName: 'An',
      phone: '0912345678',
      avatarUrl: 'https://example.com/new.png',
      dateOfBirth: current.dateOfBirth,
      gender: current.gender,
    })
  })

  it('removes the avatar when the field is emptied', async () => {
    const { manager, profiles } = await loggedIn()
    await manager.saveProfile({ fullName: 'An', phone: '', avatarUrl: '' })
    expect(profiles.updateMyProfile.mock.calls[0][0].avatarUrl).toBeNull()
  })

  it('keeps the avatar when a save does not mention it', async () => {
    const { manager, profiles } = await loggedIn()
    await manager.saveProfile({ fullName: 'An', phone: '' })
    expect(profiles.updateMyProfile.mock.calls[0][0].avatarUrl).toBe(current.avatarUrl)
  })

  it('uses a new idempotency key when only the avatar changes after a failure', async () => {
    const makeKey = vi.fn().mockReturnValueOnce('key-1').mockReturnValueOnce('key-2')
    const { manager, profiles } = await loggedIn({ makeKey })
    profiles.updateMyProfile.mockRejectedValueOnce(new Error('timeout'))
    const first = { fullName: 'An', phone: '', avatarUrl: 'https://example.com/a.png' }
    await expect(manager.saveProfile(first)).rejects.toThrow('timeout')
    await manager.saveProfile({ ...first, avatarUrl: 'https://example.com/b.png' })
    const [attempt, retry] = profiles.updateMyProfile.mock.calls
    expect(attempt[1]).toBe('key-1')
    expect(retry[1]).toBe('key-2')
    expect(retry[0].avatarUrl).toBe('https://example.com/b.png')
  })
})
