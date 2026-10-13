import { expect, test } from '@playwright/test'
import {
  addShop,
  allowOtpResend,
  createAccount,
  emailCode,
  expireOtp,
  expireRefresh,
  expireReset,
  login,
  newAccount,
  openAccountMenu,
  suspendAccount,
} from './backend.js'

async function fillRegistration(page, account) {
  await page.goto('/register')
  await page.getByLabel('Tên tài khoản', { exact: true }).fill(account.username)
  await page.getByLabel('Họ và tên', { exact: true }).fill(account.fullName)
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await page.getByLabel('Số điện thoại', { exact: true }).fill(account.phone)
  await page.getByLabel('Mật khẩu', { exact: true }).fill(account.password)
  await page.getByLabel('Xác nhận mật khẩu', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
  await expect(page).toHaveURL(/\/verify-email/)
}

async function submitOtp(page, otp) {
  await page.getByLabel(/mã xác thực|mã otp/i).fill(otp)
  await page.getByRole('button', { name: /xác thực|xác nhận/i }).click()
}

async function submitReset(page, email, code, password) {
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel(/mã xác thực|mã đặt lại|mã otp/i).fill(code)
  await page.getByLabel('Mật khẩu mới', { exact: true }).fill(password)
  await page.getByLabel('Xác nhận mật khẩu', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Lưu mật khẩu mới', exact: true }).click()
}

async function observeLogout(page) {
  await page.evaluate(() => {
    window.__logoutEvents = 0
    window.__authCheckpoint = new Promise((resolve) => {
      const channel = new BroadcastChannel('vmarket-auth')
      channel.onmessage = ({ data }) => {
        if (data?.type === 'logout') window.__logoutEvents += 1
        if (data?.type === 'e2e-checkpoint') resolve()
      }
      window.__authObserver = channel
    })
  })
}

async function expectNoLogout(source, observer) {
  await source.evaluate(() => {
    const channel = new BroadcastChannel('vmarket-auth')
    channel.postMessage({ type: 'e2e-checkpoint' })
    channel.close()
  })
  await observer.evaluate(() => window.__authCheckpoint)
  expect(await observer.evaluate(() => window.__logoutEvents)).toBe(0)
  await expect(observer).toHaveURL(/\/profile/)
  await expect(observer.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
}

test('registration draft survives OTP page reload; real profile and HttpOnly session persist', async ({
  page,
}) => {
  const account = newAccount()
  await fillRegistration(page, account)
  await page.reload()
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue(account.email)
  const draftStorage = await page.evaluate(() => JSON.stringify({ ...sessionStorage }))
  expect(draftStorage).toContain(account.fullName)
  expect(draftStorage).toContain(account.phone)
  expect(draftStorage).not.toContain(account.password)
  expect(draftStorage).not.toContain('accessToken')
  await submitOtp(page, await emailCode(account.email))
  await expect(page).not.toHaveURL(/\/verify-email/)
  await page.goto('/profile')
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue(account.fullName)
  await expect(page.getByLabel('Số điện thoại', { exact: true })).toHaveValue(account.phone)
  const refresh = (await page.context().cookies()).find((cookie) => cookie.name === 'refresh_token')
  expect(refresh?.httpOnly).toBe(true)
  expect(refresh?.secure).toBe(false)
  for (let cycle = 0; cycle < 5; cycle += 1) {
    await page.reload()
    await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue(account.fullName)
  }
  const browserStorage = await page.evaluate(() => ({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }))
  expect(JSON.stringify(browserStorage)).not.toContain('accessToken')
  expect(JSON.stringify(browserStorage)).not.toContain(account.password)
})

test('pending password login only opens email verification and can request a code', async ({
  page,
}) => {
  const account = await createAccount(page.request, { verified: false })
  await login(page, account)
  await expect(page).toHaveURL(/\/verify-email/)
  await page.goto('/profile')
  await expect(page).toHaveURL(/\/verify-email/)
  await page.getByRole('button', { name: /gửi.*mã/i }).click()
  await submitOtp(page, await emailCode(account.email))
  await expect(page).not.toHaveURL(/\/verify-email/)
})

test('registration survives OTP delivery interruption without registering twice', async ({
  page,
}) => {
  const account = newAccount()
  let registrations = 0
  let requests = 0
  page.on('request', (request) => {
    if (request.url().endsWith('/api/auth/register')) registrations += 1
  })
  await page.route('**/api/auth/otp/request', (route) => {
    requests += 1
    return requests === 1 ? route.abort('failed') : route.continue()
  })
  await fillRegistration(page, account)
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByRole('button', { name: /gửi.*mã/i }).click()
  await submitOtp(page, await emailCode(account.email))
  await expect(page).not.toHaveURL(/\/verify-email/)
  expect(registrations).toBe(1)
  expect(requests).toBe(2)
})

test('post-verification profile response loss retries profile only with stable idempotency', async ({
  page,
}) => {
  const account = newAccount()
  const writes = []
  let verifications = 0
  page.on('request', (request) => {
    if (request.url().endsWith('/api/auth/otp/verify')) verifications += 1
  })
  await page.route('**/api/users/me', async (route) => {
    if (route.request().method() !== 'PUT') return route.continue()
    writes.push({
      key: route.request().headers()['idempotency-key'],
      body: route.request().postDataJSON(),
    })
    if (writes.length === 1) {
      await route.fetch()
      return route.abort('failed')
    }
    return route.continue()
  })
  await fillRegistration(page, account)
  await submitOtp(page, await emailCode(account.email))
  await expect(
    page.getByRole('heading', { name: 'Tài khoản đã sẵn sàng', exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: 'Thử lại', exact: true }).click()
  await expect(page).not.toHaveURL(/\/verify-email/)
  expect(verifications).toBe(1)
  expect(writes).toHaveLength(2)
  expect(writes[0].key).toBeTruthy()
  expect(writes[1]).toEqual(writes[0])
  await page.goto('/profile')
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue(account.fullName)
})

test('duplicate email and username are explained without creating another account', async ({
  page,
}) => {
  const existing = await createAccount(page.request)
  for (const field of ['email', 'username']) {
    const duplicate = { ...newAccount(), [field]: existing[field] }
    await page.goto('/register')
    await page.getByLabel('Tên tài khoản', { exact: true }).fill(duplicate.username)
    await page.getByLabel('Họ và tên', { exact: true }).fill(duplicate.fullName)
    await page.getByLabel('Email', { exact: true }).fill(duplicate.email)
    await page.getByLabel('Số điện thoại', { exact: true }).fill(duplicate.phone)
    await page.getByLabel('Mật khẩu', { exact: true }).fill(duplicate.password)
    await page.getByLabel('Xác nhận mật khẩu', { exact: true }).fill(duplicate.password)
    await page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText(
      new RegExp(`${field}.*(tồn tại|sử dụng|đăng ký)`, 'i'),
    )
    await expect(page).toHaveURL(/\/register/)
  }
})

test('wrong, exhausted and expired OTP; resend issues a real fresh code', async ({ page }) => {
  const account = newAccount()
  await fillRegistration(page, account)
  const correct = await emailCode(account.email)
  const wrong = correct === '000000' ? '111111' : '000000'
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const response = page.waitForResponse((res) => res.url().endsWith('/api/auth/otp/verify'))
    await submitOtp(page, wrong)
    expect((await response).status()).toBe(400)
  }
  await expect(page.getByRole('alert')).toContainText(/quá nhiều|hết lượt/i)
  allowOtpResend(account.email)
  // Request through the real API to avoid waiting for the UI's 60-second timer.
  expect(
    (await page.request.post('/api/auth/otp/request', { data: { email: account.email } })).ok(),
  ).toBe(true)
  const fresh = await emailCode(account.email)
  expireOtp(account.email)
  await submitOtp(page, fresh)
  await expect(page.getByRole('alert')).toContainText(/hết hạn/i)
  allowOtpResend(account.email)
  expect(
    (await page.request.post('/api/auth/otp/request', { data: { email: account.email } })).ok(),
  ).toBe(true)
  await submitOtp(page, await emailCode(account.email))
  await expect(page).not.toHaveURL(/\/verify-email/)
})

test('five failed email logins block new password login; password reset clears temporary lock', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await page.goto('/login')
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await page.getByLabel('Mật khẩu', { exact: true }).fill('WrongPassword1!')
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const response = page.waitForResponse((res) => res.url().endsWith('/api/auth/login'))
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click()
    expect((await response).status()).toBe(attempt === 4 ? 423 : 401)
  }
  await expect(page.getByRole('alert')).toContainText(/(khoá|khóa).*phút/i)
  await login(page, account)
  await expect(page.getByRole('alert')).toContainText(/(khoá|khóa).*phút/i)
  await page.goto('/forgot-password')
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await page.getByRole('button', { name: /gửi.*mã/i }).click()
  await expect(page).toHaveURL(/\/reset-password/)
  const password = 'UpdatedPassword2!'
  await submitReset(page, account.email, await emailCode(account.email, 'reset'), password)
  await expect(page).toHaveURL(/\/login/)
  await login(page, { ...account, password })
  await expect(page).not.toHaveURL(/\/login/)
})

test('email login is case insensitive; username and identifier payloads are rejected', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  for (const data of [
    { email: account.username, password: account.password },
    { identifier: account.email, password: account.password },
  ]) {
    const response = await page.request.post('/api/auth/login', { data })
    expect(response.status()).toBe(400)
    expect((await response.json()).error.code).toBe('VALIDATION_ERROR')
  }
  await login(page, account, { email: account.email.toUpperCase() })
  await page.goto('/profile')
  await expect(
    page.getByRole('region', { name: 'Hồ sơ cá nhân' }).getByText(account.email, { exact: true }),
  ).toBeVisible()
  await openAccountMenu(page)
  await page.getByRole('menuitem', { name: 'Đăng xuất', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
  await login(page, account)
  await page.goto('/profile')
  await expect(
    page
      .getByRole('region', { name: 'Hồ sơ cá nhân' })
      .getByText(account.username, { exact: true }),
  ).toBeVisible()
})

test('attacker locks new password logins without revoking sessions on either existing device', async ({
  page,
  browser,
  baseURL,
}) => {
  const account = await createAccount(page.request)
  const secondDevice = await browser.newContext({ baseURL })
  const attacker = await browser.newContext({ baseURL })
  try {
    const second = await secondDevice.newPage()
    for (const device of [page, second]) {
      await login(device, account)
      await device.goto('/profile')
      await expect(
        device.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true }),
      ).toBeVisible()
    }
    const cookies = await Promise.all(
      [page.context(), secondDevice].map(async (context) =>
        (await context.cookies()).find((cookie) => cookie.name === 'refresh_token'),
      ),
    )
    expect(cookies[0].value).not.toBe(cookies[1].value)
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const response = await attacker.request.post('/api/auth/login', {
        data: { email: account.email, password: 'WrongPassword1!' },
      })
      expect(response.status()).toBe(attempt === 4 ? 423 : 401)
    }
    const blocked = await attacker.request.post('/api/auth/login', {
      data: { email: account.email, password: account.password },
    })
    expect(blocked.status()).toBe(423)
    expect((await blocked.json()).error.code).toBe('ACCOUNT_LOCKED')
    for (const [index, device] of [page, second].entries()) {
      const refreshed = device.waitForResponse((response) =>
        response.url().endsWith('/api/auth/refresh'),
      )
      await device.reload()
      const response = await refreshed
      expect(response.status()).toBe(200)
      await response.finished()
      await expect(
        device.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true }),
      ).toBeVisible()
      const currentCookie = (await device.context().cookies()).find(
        (cookie) => cookie.name === 'refresh_token',
      )
      expect(currentCookie.value).not.toBe(cookies[index].value)
      await expect(
        device
          .getByRole('region', { name: 'Hồ sơ cá nhân' })
          .getByText(account.email, { exact: true }),
      ).toBeVisible()
    }
  } finally {
    await secondDevice.close()
    await attacker.close()
  }
})

test('a guest tab with a missing refresh cookie does not broadcast logout to an active tab', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/profile')
  await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
  await observeLogout(page)
  const guest = await page.context().newPage()
  // Simulate this tab having no cookie without deleting the active tab's real shared cookie.
  await guest.route('**/api/auth/refresh', (route) =>
    route.fulfill({
      status: 401,
      json: { error: { code: 'REFRESH_TOKEN_MISSING', message: 'Thiếu refresh token' } },
    }),
  )
  const refreshed = guest.waitForResponse((response) =>
    response.url().endsWith('/api/auth/refresh'),
  )
  await guest.goto('/login')
  expect((await refreshed).status()).toBe(401)
  await expect(guest.getByLabel('Email', { exact: true })).toBeVisible()
  await expectNoLogout(guest, page)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
})

test('resetting another account in a guest tab preserves the signed-in account in the other tab', async ({
  page,
}) => {
  const signedIn = await createAccount(page.request)
  const resetAccount = await createAccount(page.request)
  await login(page, signedIn)
  await page.goto('/profile')
  await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
  expect(
    (
      await page.request.post('/api/auth/password/forgot', { data: { email: resetAccount.email } })
    ).ok(),
  ).toBe(true)
  const code = await emailCode(resetAccount.email, 'reset')
  await observeLogout(page)
  const guest = await page.context().newPage()
  // Keep this tab's local session empty; the other tab still holds a real valid cookie/session.
  await guest.route('**/api/auth/refresh', (route) => route.abort('failed'))
  const unavailable = guest.waitForEvent('requestfailed', {
    predicate: (request) => request.url().endsWith('/api/auth/refresh'),
  })
  await guest.goto('/reset-password')
  await unavailable
  await submitReset(guest, resetAccount.email, code, 'UpdatedPassword2!')
  await expect(guest).toHaveURL(/\/login/)
  await expectNoLogout(guest, page)
  await page.reload()
  await expect(
    page.getByRole('region', { name: 'Hồ sơ cá nhân' }).getByText(signedIn.email, { exact: true }),
  ).toBeVisible()
})

test('forgot-password conceals unknown email and reset explains expiration', async ({ page }) => {
  const account = await createAccount(page.request)
  const messages = []
  for (const email of [newAccount().email, account.email]) {
    await page.goto('/forgot-password')
    await page.getByLabel('Email', { exact: true }).fill(email)
    const response = page.waitForResponse((res) => res.url().endsWith('/api/auth/password/forgot'))
    await page.getByRole('button', { name: /gửi.*mã/i }).click()
    messages.push((await (await response).json()).message)
    await expect(page).toHaveURL(/\/reset-password/)
  }
  expect(messages[0]).toBe(messages[1])
  const code = await emailCode(account.email, 'reset')
  expireReset(account.id)
  await submitReset(page, account.email, code, 'UpdatedPassword2!')
  await expect(page.getByRole('alert')).toContainText(/hết hạn/i)
})

test('administrative suspension remains after password reset', async ({ page }) => {
  const account = await createAccount(page.request)
  suspendAccount(account.id)
  await login(page, account)
  await expect(page.getByRole('alert')).toContainText(/quản trị|tạm ngưng|đình chỉ/i)
  await page.goto('/forgot-password')
  await page.getByLabel('Email', { exact: true }).fill(account.email)
  await page.getByRole('button', { name: /gửi.*mã/i }).click()
  await expect(page).toHaveURL(/\/reset-password/)
  const password = 'UpdatedPassword2!'
  await submitReset(page, account.email, await emailCode(account.email, 'reset'), password)
  await expect(page).toHaveURL(/\/login/)
  await login(page, { ...account, password })
  await expect(page.getByRole('alert')).toContainText(/quản trị|tạm ngưng|đình chỉ/i)
})

test('profile retries same request and preserves avatar, birth date and gender', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  const profile = {
    fullName: account.fullName,
    phone: account.phone,
    avatarUrl: 'https://example.com/avatar.png',
    dateOfBirth: '2003-05-17',
    gender: 'FEMALE',
  }
  expect(
    (
      await page.request.put('/api/users/me', {
        headers: {
          Authorization: `Bearer ${account.accessToken}`,
          'Idempotency-Key': account.username,
        },
        data: profile,
      })
    ).ok(),
  ).toBe(true)
  await login(page, account)
  await page.goto('/profile')
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue(profile.fullName)
  const attempts = []
  await page.route('**/api/users/me', async (route) => {
    if (route.request().method() !== 'PUT') return route.continue()
    attempts.push({
      key: route.request().headers()['idempotency-key'],
      body: route.request().postDataJSON(),
    })
    if (attempts.length === 1) {
      // The write really commits, then its response is lost. Retry must be idempotent.
      await route.fetch()
      return route.abort('failed')
    }
    return route.continue()
  })
  await page.getByLabel('Họ và tên', { exact: true }).fill('Tên đã chỉnh sửa')
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  await expect(page.getByRole('status')).toContainText(/thành công|đã.*lưu/i)
  expect(attempts).toHaveLength(2)
  expect(attempts[0].key).toBeTruthy()
  expect(attempts[1]).toEqual(attempts[0])
  expect(attempts[1].body).toMatchObject({ ...profile, fullName: 'Tên đã chỉnh sửa' })
  await page.reload()
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue('Tên đã chỉnh sửa')
})

test('two tabs repeatedly restore sessions without refresh token reuse; logout propagates', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/profile')
  await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
  const second = await page.context().newPage()
  for (let cycle = 0; cycle < 5; cycle += 1) {
    await Promise.all([page.reload(), second.goto('/profile')])
    await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
    await expect(second.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
  }
  await openAccountMenu(page)
  await page.getByRole('menuitem', { name: 'Đăng xuất', exact: true }).click()
  await expect(second).toHaveURL(/\/login/)
  await page.goto('/profile')
  await expect(page).toHaveURL(/\/login/)
})

test('short-lived real access token is refreshed automatically before expiry', async ({ page }) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await expect(page).not.toHaveURL(/\/login/)
  const before = (await page.context().cookies()).find((cookie) => cookie.name === 'refresh_token')
  const refresh = await page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/auth/refresh') && response.request().method() === 'POST',
    { timeout: 35_000 },
  )
  expect(refresh.status()).toBe(200)
  await refresh.finished()
  const after = (await page.context().cookies()).find((cookie) => cookie.name === 'refresh_token')
  expect(after.value).not.toBe(before.value)
  await page.goto('/profile')
  await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
})

test('temporary refresh and logout network failures offer retry without losing the session', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await expect(page).not.toHaveURL(/\/login/)
  let refreshes = 0
  await page.route('**/api/auth/refresh', (route) => {
    refreshes += 1
    return refreshes === 1 ? route.abort('failed') : route.continue()
  })
  await page.goto('/profile')
  await expect(
    page.getByRole('heading', { name: 'Chưa thể khôi phục phiên', exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: 'Thử lại', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
  let logouts = 0
  await page.route('**/api/auth/logout', (route) => {
    logouts += 1
    return logouts === 1 ? route.abort('failed') : route.continue()
  })
  await openAccountMenu(page)
  await page.getByRole('menuitem', { name: 'Đăng xuất', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText(/thử lại/i)
  await expect(page).toHaveURL(/\/profile/)
  await page.getByRole('menuitem', { name: 'Đăng xuất', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
  expect(logouts).toBe(2)
})

test('expired refresh returns to login; returnTo only accepts internal authorized routes', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await page.goto('/profile')
  await expect(page).toHaveURL(/\/login/)
  await login(page, account, { navigate: false })
  await expect(page).toHaveURL(/\/profile/)
  await expect(page.getByRole('heading', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
  expireRefresh(account.id)
  await page.reload()
  await expect(page).toHaveURL(/\/login/)
  await page.goto('/login?returnTo=https://example.com')
  await login(page, account)
  await expect(page).not.toHaveURL(/example\.com/)
  await page.goto('/admin')
  await expect(
    page.getByRole('heading', { name: 'Bạn chưa có quyền truy cập', exact: true }),
  ).toBeVisible()
})

test('role dropdown and direct-route guards use real roles', async ({ page }) => {
  const account = await createAccount(page.request, { roles: ['ADMIN', 'SHIPPER'] })
  await login(page, account)
  await openAccountMenu(page)
  await expect(page.getByRole('menuitem', { name: 'Hồ sơ cá nhân', exact: true })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: 'Khu vực quản trị', exact: true })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: 'Khu vực giao hàng', exact: true })).toBeVisible()
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/admin/)
  await page.goto('/shipper')
  await expect(page).toHaveURL(/\/shipper/)
})

test('buyer without a shop opens intro; backend failure offers retry instead of intro', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/seller')
  await expect(page).toHaveURL(/\/seller\/register/)
  await page.route('**/api/shops/me', (route) => route.abort('failed'))
  await page.goto('/seller')
  await expect(page.getByRole('button', { name: /thử lại/i })).toBeVisible()
  await expect(page).toHaveURL(/\/seller$/)
})

test('buyer with pending shop sees actual review status; active seller opens seller area', async ({
  page,
}) => {
  const pending = await createAccount(page.request)
  addShop(pending.id)
  await login(page, pending)
  await page.goto('/seller')
  await expect(page.getByText(/chờ.*duyệt/i).first()).toBeVisible()
  await openAccountMenu(page)
  await page.getByRole('menuitem', { name: 'Đăng xuất', exact: true }).click()
  const active = await createAccount(page.request, { roles: ['SELLER'] })
  addShop(active.id, 'ACTIVE')
  await login(page, active)
  await page.goto('/seller')
  await expect(page).toHaveURL(/\/seller$/)
  await expect(
    page.getByRole('heading', { name: /kênh người bán|gian hàng/i }).first(),
  ).toBeVisible()
})

test('auth forms remain usable at narrow width with keyboard and omit deferred controls', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/login')
  await expect(page.getByLabel('Email', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Đăng nhập', exact: true })).toBeVisible()
  await expect(page.getByRole('checkbox')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /google/i })).toHaveCount(0)
  await page.getByLabel('Email', { exact: true }).focus()
  await page.keyboard.press('Tab')
  await expect(page.getByLabel('Mật khẩu', { exact: true })).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
})
