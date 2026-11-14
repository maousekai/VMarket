import { expect, test } from '@playwright/test'
import { randomInt } from 'node:crypto'
import { createAccount, login, newAccount } from './backend.js'

// Test names carry the case IDs of the "Web Test" sheet in VMarket_Test_Report.xlsx.

const LABELS = {
  username: 'Tên tài khoản',
  fullName: 'Họ và tên',
  phone: 'Số điện thoại',
  email: 'Email',
  password: 'Mật khẩu',
  confirmPassword: 'Xác nhận mật khẩu',
}

const field = (page, name) => page.getByLabel(LABELS[name], { exact: true })
const submitRegistration = (page) =>
  page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
const saveProfile = (page) =>
  page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()

function randomUsername(length) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'
  return Array.from({ length }, () => alphabet[randomInt(alphabet.length)]).join('')
}

// Keeps the upper-case, digit and special-character groups at every length.
const passwordOfLength = (length) => `Aa1!${'x'.repeat(length - 4)}`

function trackRegistrations(page) {
  const posts = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/auth/register')) {
      posts.push(request.postDataJSON())
    }
  })
  return posts
}

function trackProfileWrites(page) {
  const puts = []
  page.on('request', (request) => {
    if (request.method() === 'PUT' && request.url().endsWith('/api/users/me')) {
      puts.push(request.postDataJSON())
    }
  })
  return puts
}

async function fillRegistration(page, values) {
  for (const name of ['username', 'fullName', 'email', 'phone']) {
    await field(page, name).fill(values[name])
  }
  await fillPasswords(page, values.password)
}

async function fillPasswords(page, password, confirmation = password) {
  await field(page, 'password').fill(password)
  await field(page, 'confirmPassword').fill(confirmation)
}

async function expectRejected(page, posts, invalidFields) {
  const before = posts.length
  await submitRegistration(page)
  for (const name of Object.keys(LABELS)) {
    await expect(field(page, name)).toHaveAttribute(
      'aria-invalid',
      String(invalidFields.includes(name)),
    )
  }
  await expect(page).toHaveURL(/\/register/)
  expect(posts).toHaveLength(before)
}

async function expectRegistered(page, posts, count) {
  await submitRegistration(page)
  await expect(page).toHaveURL(/\/verify-email/)
  expect(posts).toHaveLength(count)
}

async function openProfile(page, account) {
  await login(page, account)
  await page.goto('/profile')
  await expect(field(page, 'fullName')).toBeVisible()
}

async function expectSaved(page) {
  await saveProfile(page)
  await expect(page.getByRole('status')).toContainText(/đã.*lưu/i)
}

test('WEB-REG-02 required fields and invalid emails are marked without calling register', async ({
  page,
}) => {
  const posts = trackRegistrations(page)
  const account = newAccount()
  await page.goto('/register')
  await expectRejected(page, posts, ['username', 'email', 'password'])

  await fillRegistration(page, account)
  for (const email of ['abc', 'a@', 'a b@x.com']) {
    await field(page, 'email').fill(email)
    await expectRejected(page, posts, ['email'])
  }

  await field(page, 'email').fill(account.email)
  await expectRegistered(page, posts, 1)
})

test('WEB-REG-03 username accepts 3 and 50 characters and blocks 2, spaces and over-length', async ({
  page,
}) => {
  const posts = trackRegistrations(page)
  await page.goto('/register')
  await fillRegistration(page, newAccount())
  for (const username of [randomUsername(2), 'abc def']) {
    await field(page, 'username').fill(username)
    await expectRejected(page, posts, ['username'])
  }

  for (const [index, length] of [3, 50].entries()) {
    const username = randomUsername(length)
    await page.goto('/register')
    await fillRegistration(page, { ...newAccount(), username })
    await expectRegistered(page, posts, index + 1)
    expect(posts.at(-1).username).toBe(username)
  }

  // A real keystroke stream is what maxLength truncates; fill() would not exercise it.
  const typed = randomUsername(51)
  await page.goto('/register')
  await fillRegistration(page, { ...newAccount(), username: 'placeholder' })
  await field(page, 'username').fill('')
  await field(page, 'username').pressSequentially(typed)
  await expect(field(page, 'username')).toHaveValue(typed.slice(0, 50))
  await expectRegistered(page, posts, 3)
  expect(posts.at(-1).username).toBe(typed.slice(0, 50))
})

test('WEB-REG-04 password accepts 8 and 32 characters and blocks 7, 33 and missing groups', async ({
  page,
}) => {
  const posts = trackRegistrations(page)
  await page.goto('/register')
  await fillRegistration(page, newAccount())
  for (const password of [
    passwordOfLength(7),
    passwordOfLength(33),
    'abc12345!',
    'Abcdefgh!',
    'Abcdefg1',
  ]) {
    await fillPasswords(page, password)
    await expectRejected(page, posts, ['password'])
  }

  // The last value has no lower-case letter, which the current rule does not require.
  const accepted = [passwordOfLength(8), passwordOfLength(32), 'ABCDEF1!']
  for (const [index, password] of accepted.entries()) {
    await page.goto('/register')
    await fillRegistration(page, { ...newAccount(), password })
    await expectRegistered(page, posts, index + 1)
    expect(posts.at(-1).password).toBe(password)
  }
})

test('WEB-REG-05 mismatched confirmation blocks register until it is corrected', async ({
  page,
}) => {
  const posts = trackRegistrations(page)
  await page.goto('/register')
  await fillRegistration(page, newAccount())
  await fillPasswords(page, 'QaVmarket1!', 'QaVmarket2!')
  await expectRejected(page, posts, ['confirmPassword'])
  await expect(page.getByText('Mật khẩu xác nhận không khớp.')).toBeVisible()

  await field(page, 'confirmPassword').fill('QaVmarket1!')
  await expectRegistered(page, posts, 1)
  expect(posts[0].password).toBe('QaVmarket1!')
})

test('WEB-PROFILE-03 full name saves 99 and 100 characters and never sends more', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  const rejected = await page.request.put('/api/users/me', {
    headers: {
      Authorization: `Bearer ${account.accessToken}`,
      'Idempotency-Key': account.username,
    },
    data: { fullName: 'a'.repeat(101), phone: '0912345678' },
  })
  expect(rejected.status()).toBe(400)
  expect(await rejected.text()).toContain('tối đa 100')

  const puts = trackProfileWrites(page)
  await openProfile(page, account)
  for (const length of [99, 100]) {
    const fullName = randomUsername(length)
    await field(page, 'fullName').fill(fullName)
    await expectSaved(page)
    await page.reload()
    await expect(field(page, 'fullName')).toHaveValue(fullName)
  }

  const typed = randomUsername(101)
  await field(page, 'fullName').fill('')
  await field(page, 'fullName').pressSequentially(typed)
  await expect(field(page, 'fullName')).toHaveValue(typed.slice(0, 100))
  await expectSaved(page)
  expect(puts.at(-1).fullName).toBe(typed.slice(0, 100))
})

test('WEB-PROFILE-04 phone accepts 0 and +84 forms; invalid values never reach the server', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  const puts = trackProfileWrites(page)
  await openProfile(page, account)

  for (const phone of ['0912345678', '+84912345678']) {
    await field(page, 'phone').fill(phone)
    await expectSaved(page)
    await page.reload()
    await expect(field(page, 'phone')).toHaveValue(phone)
  }
  expect(puts).toHaveLength(2)

  for (const phone of ['091234567', '09123456789', 'abcdefghij']) {
    await field(page, 'phone').fill(phone)
    await saveProfile(page)
    await expect(field(page, 'phone')).toHaveAttribute('aria-invalid', 'true')
    await expect(page.getByText('Số điện thoại phải là số di động Việt Nam hợp lệ.')).toBeVisible()
  }
  expect(puts).toHaveLength(2)

  await page.reload()
  await expect(field(page, 'phone')).toHaveValue('+84912345678')
})

test('WEB-PROFILE-06 a failed profile load shows an error, not an empty form, and retry recovers', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  const profile = { fullName: 'Hồ Sơ Mẫu A', phone: '0987654321' }
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
  await expect(page).not.toHaveURL(/\/login(?:[?#]|$)/)

  let failing = true
  await page.route('**/api/users/me', (route) =>
    failing && route.request().method() === 'GET'
      ? route.fulfill({
          status: 503,
          json: { error: { code: 'SERVICE_UNAVAILABLE', message: 'Dịch vụ tạm thời gián đoạn' } },
        })
      : route.continue(),
  )
  await page.goto('/profile')
  await expect(page.getByRole('heading', { name: 'Chưa thể tải hồ sơ', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(field(page, 'fullName')).toHaveCount(0)

  failing = false
  await page.getByRole('button', { name: 'Thử lại', exact: true }).click()
  await expect(field(page, 'fullName')).toHaveValue(profile.fullName)
  await expect(field(page, 'phone')).toHaveValue(profile.phone)
})
