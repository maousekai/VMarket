import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import {
  createAccount,
  emailCode,
  expireOtp,
  login,
  newAccount,
  openAccountMenu,
} from './backend.js'

// One test per remaining case of the "Web Test" sheet; each asserts that case's Expected Results.

const composePath = fileURLToPath(new URL('./compose.yml', import.meta.url))
const profileHeading = { name: 'Hồ sơ cá nhân', exact: true }

function authCount(column, value) {
  if (!['email', 'username'].includes(column) || !/^[a-zA-Z0-9_.@-]+$/.test(value)) {
    throw new Error('Unsafe fixture lookup')
  }
  return execFileSync(
    'docker',
    [
      'compose',
      '-p',
      'vmarket-auth-e2e',
      '-f',
      composePath,
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      'vmarket',
      '-d',
      'vmarket_auth',
      '-At',
      '-c',
      `select count(*) from users where ${column}='${value}'`,
    ],
    { encoding: 'utf8', timeout: 30_000 },
  ).trim()
}

const field = (page, label) => page.getByLabel(label, { exact: true })
const loginButton = (page) => page.getByRole('button', { name: 'Đăng nhập', exact: true })
const identity = (page) => page.getByRole('region', { name: 'Hồ sơ cá nhân' })
const refreshCookie = async (context) =>
  (await context.cookies()).find((cookie) => cookie.name === 'refresh_token')

function trackPosts(page, path) {
  const statuses = []
  page.on('response', (response) => {
    if (response.request().method() === 'POST' && response.url().endsWith(path)) {
      statuses.push(response.status())
    }
  })
  return statuses
}

const postResponse = (page, path) =>
  page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith(path),
  )

async function fillRegistration(page, account) {
  await page.goto('/register')
  await field(page, 'Tên tài khoản').fill(account.username)
  await field(page, 'Họ và tên').fill(account.fullName)
  await field(page, 'Email').fill(account.email)
  await field(page, 'Số điện thoại').fill(account.phone)
  await field(page, 'Mật khẩu').fill(account.password)
  await field(page, 'Xác nhận mật khẩu').fill(account.password)
}

const submitRegistration = (page) =>
  page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()

async function registerToOtpPage(page, account) {
  await fillRegistration(page, account)
  await submitRegistration(page)
  await expect(page).toHaveURL(/\/verify-email/)
}

async function submitOtp(page, otp) {
  const response = postResponse(page, '/api/auth/otp/verify')
  await field(page, 'Mã xác thực').fill(otp)
  await page.getByRole('button', { name: 'Xác thực email', exact: true }).click()
  return response
}

async function submitLogin(page, email, password) {
  await field(page, 'Email').fill(email)
  await field(page, 'Mật khẩu').fill(password)
  const response = postResponse(page, '/api/auth/login')
  await loginButton(page).click()
  const result = await response
  await result.finished()
  return result
}

async function signOut(page) {
  await openAccountMenu(page)
  await page.getByRole('menuitem', { name: 'Đăng xuất', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
}

async function putProfile(request, account, profile) {
  const response = await request.put('/api/users/me', {
    headers: {
      Authorization: `Bearer ${account.accessToken}`,
      'Idempotency-Key': account.username,
    },
    data: profile,
  })
  expect(response.ok()).toBe(true)
}

async function accountWithProfile(request, profile) {
  const account = await createAccount(request)
  await putProfile(request, account, profile)
  return { ...account, ...profile }
}

async function loadProfileGet(page, navigate) {
  const response = page.waitForResponse(
    (result) => result.request().method() === 'GET' && result.url().endsWith('/api/users/me'),
  )
  await navigate()
  const result = await response
  expect(result.status()).toBe(200)
  return result.json()
}

test('WEB-REG-01 register, verify the real OTP and land on the correct single account', async ({
  page,
}) => {
  const account = newAccount()
  const registrations = trackPosts(page, '/api/auth/register')
  await registerToOtpPage(page, account)
  expect(registrations).toEqual([201])

  const verified = await submitOtp(page, await emailCode(account.email))
  expect(verified.status()).toBe(200)
  await expect(page).not.toHaveURL(/\/verify-email/)

  await page.goto('/profile')
  await expect(identity(page).getByText(account.email, { exact: true })).toBeVisible()
  await expect(identity(page).getByText(account.username, { exact: true })).toBeVisible()
  expect(authCount('email', account.email)).toBe('1')
  expect(authCount('username', account.username)).toBe('1')
})

test('WEB-REG-06 duplicate email or username returns 409 and creates nothing', async ({ page }) => {
  const existing = await createAccount(page.request)
  for (const [taken, fresh] of [
    ['email', 'username'],
    ['username', 'email'],
  ]) {
    const duplicate = { ...newAccount(), [taken]: existing[taken] }
    await fillRegistration(page, duplicate)
    const response = postResponse(page, '/api/auth/register')
    await submitRegistration(page)
    expect((await response).status()).toBe(409)
    await expect(page.getByRole('alert')).toContainText(
      new RegExp(`${taken}.*(tồn tại|sử dụng|đăng ký)`, 'i'),
    )
    await expect(page).toHaveURL(/\/register/)
    expect(authCount(taken, existing[taken])).toBe('1')
    expect(authCount(fresh, duplicate[fresh])).toBe('0')
  }
})

test('WEB-REG-07 wrong, expired and already used OTP never grant a verified session', async ({
  page,
  browser,
  baseURL,
}) => {
  const account = newAccount()
  await registerToOtpPage(page, account)
  const code = await emailCode(account.email)
  const wrong = await submitOtp(page, code === '000000' ? '111111' : '000000')
  expect(wrong.status()).toBe(400)
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page).toHaveURL(/\/verify-email/)
  expect(await refreshCookie(page.context())).toBeUndefined()

  const verified = await submitOtp(page, code)
  expect(verified.status()).toBe(200)
  await expect(page).not.toHaveURL(/\/verify-email/)

  const reused = await page.request.post('/api/auth/otp/verify', {
    data: { email: account.email, otp: code },
  })
  expect(reused.status()).toBe(400)
  const reusedBody = await reused.json()
  expect(reusedBody.error.code).toBe('OTP_ALREADY_USED')
  expect(reusedBody.accessToken).toBeUndefined()

  const expiredContext = await browser.newContext({ baseURL })
  try {
    const expiredPage = await expiredContext.newPage()
    const expiring = newAccount()
    await registerToOtpPage(expiredPage, expiring)
    const expiringCode = await emailCode(expiring.email)
    expireOtp(expiring.email)
    const expired = await submitOtp(expiredPage, expiringCode)
    expect(expired.status()).toBe(400)
    await expect(expiredPage.getByRole('alert')).toContainText(/hết hạn/i)
    await expect(expiredPage).toHaveURL(/\/verify-email/)
    expect(await refreshCookie(expiredContext)).toBeUndefined()
  } finally {
    await expiredContext.close()
  }
})

test('WEB-REG-08 OTP send failure plus reload keeps the draft and never registers twice', async ({
  page,
}) => {
  const account = newAccount()
  const registrations = trackPosts(page, '/api/auth/register')
  let failing = true
  await page.route('**/api/auth/otp/request', (route) =>
    failing ? route.abort('failed') : route.continue(),
  )
  await registerToOtpPage(page, account)
  await expect(page.getByRole('alert')).toBeVisible()

  await page.reload()
  await expect(field(page, 'Email')).toHaveValue(account.email)
  failing = false
  await page.getByRole('button', { name: 'Gửi mã xác thực', exact: true }).click()
  await expect(page.getByText(/Mã xác thực đã được gửi/)).toBeVisible()

  const verified = await submitOtp(page, await emailCode(account.email))
  expect(verified.status()).toBe(200)
  await expect(page).not.toHaveURL(/\/verify-email/)
  expect(registrations).toEqual([201])

  await page.goto('/profile')
  await expect(field(page, 'Họ và tên')).toHaveValue(account.fullName)
  await expect(field(page, 'Số điện thoại')).toHaveValue(account.phone)
})

test('WEB-LOGIN-01 active buyer signs in; refresh token is only an HttpOnly cookie', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await page.goto('/login')
  const response = await submitLogin(page, account.email, account.password)
  expect(response.status()).toBe(200)
  await expect(page).not.toHaveURL(/\/login/)

  const body = await response.text()
  const cookie = await refreshCookie(page.context())
  expect(cookie?.httpOnly).toBe(true)
  expect(body).not.toContain('refreshToken')
  expect(body).not.toContain(cookie.value)

  await page.goto('/profile')
  await expect(page.getByRole('heading', profileHeading)).toBeVisible()
  await expect(identity(page).getByText(account.email, { exact: true })).toBeVisible()
  await expect(identity(page).getByText(account.username, { exact: true })).toBeVisible()
})

test('WEB-LOGIN-02 email is normalized and a username cannot replace it', async ({ page }) => {
  const account = await createAccount(page.request)
  await page.goto('/login')
  const response = await submitLogin(page, `  ${account.email.toUpperCase()}  `, account.password)
  expect(response.status()).toBe(200)
  await page.goto('/profile')
  await expect(identity(page).getByText(account.email, { exact: true })).toBeVisible()
  expect(authCount('email', account.email)).toBe('1')
  await signOut(page)

  const logins = trackPosts(page, '/api/auth/login')
  await field(page, 'Email').fill(account.username)
  await field(page, 'Mật khẩu').fill(account.password)
  await loginButton(page).click()
  await expect(field(page, 'Email')).toHaveAttribute('aria-invalid', 'true')
  expect(logins).toHaveLength(0)

  const rejected = await page.request.post('/api/auth/login', {
    data: { email: account.username, password: account.password },
  })
  expect(rejected.status()).toBe(400)
  expect((await rejected.json()).error.code).toBe('VALIDATION_ERROR')
})

test('WEB-LOGIN-03 wrong password and unknown email look identical and create no session', async ({
  browser,
  baseURL,
  request,
}) => {
  const account = await createAccount(request)
  const results = []
  for (const [email, password] of [
    [account.email, 'WrongPassword1!'],
    [newAccount().email, 'WrongPassword1!'],
  ]) {
    const context = await browser.newContext({ baseURL })
    try {
      const page = await context.newPage()
      await page.goto('/login')
      const response = await submitLogin(page, email, password)
      expect(response.status()).toBe(401)
      const alert = page.getByRole('alert')
      await expect(alert).toBeVisible()
      await expect(page).toHaveURL(/\/login/)
      expect(await refreshCookie(context)).toBeUndefined()
      results.push({ error: (await response.json()).error, alert: await alert.innerText() })
      await page.goto('/profile')
      await expect(page).toHaveURL(/\/login/)
    } finally {
      await context.close()
    }
  }
  expect(results[1].error.code).toBe(results[0].error.code)
  expect(results[1].error.message).toBe(results[0].error.message)
  expect(results[1].alert).toBe(results[0].alert)

  const context = await browser.newContext({ baseURL })
  try {
    const page = await context.newPage()
    const logins = trackPosts(page, '/api/auth/login')
    await page.goto('/login')
    await field(page, 'Email').fill(account.email)
    await loginButton(page).click()
    await expect(field(page, 'Mật khẩu')).toHaveAttribute('aria-invalid', 'true')
    expect(logins).toHaveLength(0)
    await expect(page).toHaveURL(/\/login/)
  } finally {
    await context.close()
  }
})

test('WEB-LOGIN-04 four failures are 401, the fifth and a correct password are 423', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await page.goto('/login')
  for (let attempt = 1; attempt <= 5; attempt += 1) {
    const response = await submitLogin(page, account.email, 'WrongPassword1!')
    const locked = attempt === 5
    expect(response.status()).toBe(locked ? 423 : 401)
    expect((await response.json()).error.code).toBe(
      locked ? 'ACCOUNT_LOCKED' : 'INVALID_CREDENTIALS',
    )
    await expect(loginButton(page)).toBeEnabled()
  }
  await expect(page.getByRole('alert')).toContainText(/kho[áa]/i)

  const correct = await submitLogin(page, account.email, account.password)
  expect(correct.status()).toBe(423)
  expect((await correct.json()).error.code).toBe('ACCOUNT_LOCKED')
  await expect(page.getByRole('alert')).toContainText(/kho[áa]/i)
  await expect(page).toHaveURL(/\/login/)
  await expect(loginButton(page)).toBeEnabled()
  expect(await refreshCookie(page.context())).toBeUndefined()
})

test('WEB-LOGIN-05 unverified account gets status PENDING and stays in verification', async ({
  page,
}) => {
  const account = await createAccount(page.request, { verified: false })
  await page.goto('/login')
  const response = await submitLogin(page, account.email, account.password)
  expect(response.status()).toBe(200)
  expect((await response.json()).status).toBe('PENDING')
  await expect(page).toHaveURL(/\/verify-email/)

  await page.goto('/profile')
  await expect(page).toHaveURL(/\/verify-email/)
  await expect(page.getByRole('heading', { name: 'Xác thực email', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', profileHeading)).toHaveCount(0)
})

test('WEB-LOGIN-06 session survives access token expiry without a refresh loop', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  const refreshes = trackPosts(page, '/api/auth/refresh')
  await login(page, account)
  // Opening /login as a guest legitimately gets a 401 from refresh; only the session counts here.
  refreshes.length = 0
  await page.goto('/profile')
  await expect(page.getByRole('heading', profileHeading)).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', profileHeading)).toBeVisible()

  // The access token lives 30 seconds on this stack; this waits past its real expiry.
  await page.waitForTimeout(35_000)
  await loadProfileGet(page, () => page.goto('/profile'))
  await expect(page).toHaveURL(/\/profile/)
  await expect(identity(page).getByText(account.email, { exact: true })).toBeVisible()

  expect(refreshes.length).toBeGreaterThanOrEqual(2)
  expect(refreshes.length).toBeLessThanOrEqual(6)
  expect(refreshes.filter((status) => status !== 200)).toEqual([])
})

test('WEB-LOGIN-07 logout reaches both tabs and the old refresh token is dead', async ({
  page,
  browser,
  baseURL,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/profile')
  await expect(page.getByRole('heading', profileHeading)).toBeVisible()
  const second = await page.context().newPage()
  await second.goto('/profile')
  await expect(second.getByRole('heading', profileHeading)).toBeVisible()
  const cookie = await refreshCookie(page.context())

  await signOut(page)
  await expect(second).toHaveURL(/\/login/)
  await second.reload()
  await expect(second).toHaveURL(/\/login/)
  await second.goto('/profile')
  await expect(second).toHaveURL(/\/login/)

  const outsider = await browser.newContext({ baseURL })
  try {
    const replay = await outsider.request.post('/api/auth/refresh', {
      headers: { Cookie: `refresh_token=${cookie.value}` },
    })
    expect(replay.status()).toBe(401)
  } finally {
    await outsider.close()
  }
})

test('WEB-LOGIN-08 narrow keyboard login handles double submit, network loss and retry', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await page.setViewportSize({ width: 360, height: 800 })
  await page.goto('/login')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )

  // Desktop Chromium has no on-screen keyboard; a short viewport stands in for the space it takes.
  await page.setViewportSize({ width: 360, height: 470 })
  await loginButton(page).scrollIntoViewIfNeeded()
  await expect(loginButton(page)).toBeInViewport()
  await page.setViewportSize({ width: 360, height: 800 })

  let mode = 'offline'
  const calls = { offline: 0, slow: 0 }
  await page.route('**/api/auth/login', async (route) => {
    calls[mode] += 1
    if (mode === 'offline') return route.abort('failed')
    await new Promise((resolve) => setTimeout(resolve, 800))
    return route.continue()
  })

  await field(page, 'Email').focus()
  await page.keyboard.type(account.email)
  await page.keyboard.press('Tab')
  await expect(field(page, 'Mật khẩu')).toBeFocused()
  await page.keyboard.type(account.password)
  await page.keyboard.press('Enter')
  await page.keyboard.press('Enter')
  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await alert.scrollIntoViewIfNeeded()
  await expect(alert).toBeInViewport()
  await expect(loginButton(page)).toBeEnabled()
  expect(calls.offline).toBe(1)
  await field(page, 'Mật khẩu').focus()
  await expect(field(page, 'Mật khẩu')).toBeFocused()

  mode = 'slow'
  await loginButton(page).dblclick()
  await expect(page).not.toHaveURL(/\/login/)
  expect(calls.slow).toBe(1)
})

test('WEB-PROFILE-01 profile shows only the signed-in account and identity is read-only', async ({
  page,
}) => {
  const first = await accountWithProfile(page.request, {
    fullName: 'Khách Hàng Một',
    phone: '0911111111',
  })
  const other = await accountWithProfile(page.request, {
    fullName: 'Khách Hàng Hai',
    phone: '0922222222',
  })
  await login(page, first)
  await page.goto('/profile')
  await expect(field(page, 'Họ và tên')).toHaveValue(first.fullName)
  await expect(field(page, 'Số điện thoại')).toHaveValue(first.phone)
  await expect(identity(page).getByText(first.email, { exact: true })).toBeVisible()
  await expect(identity(page).getByText(first.username, { exact: true })).toBeVisible()

  const body = page.locator('body')
  await expect(body).not.toContainText(other.email)
  await expect(body).not.toContainText(other.username)
  await expect(body).not.toContainText(other.fullName)
  await expect(page.locator('.profile-form [name="email"]')).toHaveCount(0)
  await expect(page.locator('.profile-form [name="username"]')).toHaveCount(0)
})

test('WEB-PROFILE-02 saved name and phone survive a reload and come back from the API', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/profile')
  await expect(field(page, 'Họ và tên')).toBeVisible()

  await field(page, 'Họ và tên').fill('Nguyễn An QA')
  await field(page, 'Số điện thoại').fill('0987654321')
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  await expect(page.getByRole('status')).toContainText(/đã.*lưu/i)

  const saved = await loadProfileGet(page, () => page.reload())
  expect(saved.fullName).toBe('Nguyễn An QA')
  expect(saved.phone).toBe('0987654321')
  await expect(field(page, 'Họ và tên')).toHaveValue('Nguyễn An QA')
  await expect(field(page, 'Số điện thoại')).toHaveValue('0987654321')
  await expect(identity(page).getByText(account.email, { exact: true })).toBeVisible()
})

test('WEB-PROFILE-05 changing only the name keeps fields that have no form control', async ({
  page,
}) => {
  const profile = {
    fullName: 'Tên Ban Đầu',
    phone: '0933333333',
    avatarUrl: 'https://example.com/avatar.png',
    dateOfBirth: '2002-03-04',
    gender: 'OTHER',
  }
  const account = await accountWithProfile(page.request, profile)
  await login(page, account)
  await page.goto('/profile')
  await expect(field(page, 'Họ và tên')).toHaveValue(profile.fullName)
  const before = await loadProfileGet(page, () => page.reload())
  await expect(field(page, 'Họ và tên')).toHaveValue(profile.fullName)

  const write = page.waitForRequest(
    (request) => request.method() === 'PUT' && request.url().endsWith('/api/users/me'),
  )
  await field(page, 'Họ và tên').fill('Tên Đã Đổi')
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  expect((await write).postDataJSON()).toMatchObject({ ...profile, fullName: 'Tên Đã Đổi' })
  await expect(page.getByRole('status')).toContainText(/đã.*lưu/i)

  const after = await loadProfileGet(page, () => page.reload())
  expect(after.fullName).toBe('Tên Đã Đổi')
  for (const key of ['phone', 'avatarUrl', 'dateOfBirth', 'gender']) {
    expect(after[key]).toBe(before[key])
    expect(after[key]).toBe(profile[key])
  }
})

test('WEB-PROFILE-08 guest is sent to login and the next account sees none of the previous', async ({
  page,
}) => {
  const first = await accountWithProfile(page.request, {
    fullName: 'Người Dùng Trước',
    phone: '0944444444',
  })
  const next = await accountWithProfile(page.request, {
    fullName: 'Người Dùng Sau',
    phone: '0955555555',
  })
  await login(page, first)
  await page.goto('/profile')
  await expect(field(page, 'Họ và tên')).toHaveValue(first.fullName)
  await signOut(page)

  await page.goto('/profile')
  await expect(page).toHaveURL(/\/login/)
  await login(page, next, { navigate: false })
  await expect(page).toHaveURL(/\/profile/)
  await expect(field(page, 'Họ và tên')).toHaveValue(next.fullName)
  await expect(field(page, 'Số điện thoại')).toHaveValue(next.phone)
  await expect(identity(page).getByText(next.email, { exact: true })).toBeVisible()

  const body = page.locator('body')
  await expect(body).not.toContainText(first.email)
  await expect(body).not.toContainText(first.fullName)
  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))
  expect(storage).not.toContain(first.email)
  expect(storage).not.toContain(first.fullName)
})
