import { execFileSync } from 'node:child_process'
import process from 'node:process'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { expect } from '@playwright/test'

const composePath = fileURLToPath(new URL('./compose.yml', import.meta.url))
const project = process.env.E2E_COMPOSE_PROJECT || 'vmarket-auth-e2e'
const composeArgs = ['compose', '-p', project, '-f', composePath]
const databases = ['vmarket_auth', 'vmarket_user', 'vmarket_shop']
let isolated = false

function docker(args) {
  return execFileSync('docker', args, {
    encoding: 'utf8',
    maxBuffer: 10 * 1024 * 1024,
    timeout: 30_000,
    stdio: ['pipe', 'pipe', 'pipe'],
  })
}

function serviceId(service) {
  const id = docker([...composeArgs, 'ps', '-q', service]).trim()
  if (!id || !/^[a-f0-9]+$/.test(id)) throw new Error(`E2E service ${service} is not running`)
  return id
}

export function assertIsolatedStack() {
  if (!/^vmarket-auth-e2e(?:-[a-z0-9-]+)?$/.test(project)) {
    throw new Error('E2E fixtures only run in a vmarket-auth-e2e Compose project')
  }
  const postgres = JSON.parse(docker(['inspect', serviceId('postgres')]))[0]
  // Docker may report tmpfs only in HostConfig, rather than the Mounts list.
  const dataPath = '/var/lib/postgresql/data'
  const disposableData =
    Object.hasOwn(postgres.HostConfig.Tmpfs || {}, dataPath) ||
    postgres.Mounts.some((mount) => mount.Type === 'tmpfs' && mount.Destination === dataPath)
  if (
    postgres.Config.Labels['com.docker.compose.project'] !== project ||
    !disposableData ||
    postgres.Mounts.some((mount) => mount.Destination === dataPath && mount.Type !== 'tmpfs')
  ) {
    throw new Error('Refusing E2E fixtures: PostgreSQL must use disposable tmpfs storage')
  }
  isolated = true
}

export function assertBackendHealthy() {
  for (const service of ['auth-service', 'user-service', 'shop-service', 'api-gateway']) {
    const container = JSON.parse(docker(['inspect', serviceId(service)]))[0]
    if (container.State.Health?.Status !== 'healthy') {
      throw new Error(`E2E ${service} is not healthy`)
    }
  }
}

function sql(database, statement) {
  if (!isolated) assertIsolatedStack()
  if (!databases.includes(database)) throw new Error('Unknown fixture database')
  return docker([
    'exec',
    serviceId('postgres'),
    'psql',
    '-U',
    'vmarket',
    '-d',
    database,
    '-v',
    'ON_ERROR_STOP=1',
    '-At',
    '-c',
    statement,
  ]).trim()
}

function accountId(id) {
  if (!/^[0-9A-HJKMNP-TV-Z]{26}$/.test(id)) throw new Error('Invalid fixture user ID')
  return `'${id}'`
}

function testEmail(email) {
  if (!/^[a-z0-9.-]+@e2e\.vmarket\.test$/.test(email)) throw new Error('Invalid test email')
  return `'${email}'`
}

export function newAccount() {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 16)
  return {
    email: `auth-${suffix}@e2e.vmarket.test`,
    username: `auth_${suffix}`,
    password: 'E2ePassword1!',
    fullName: 'Nguyễn Minh Anh',
    phone: '0912345678',
  }
}

export async function emailCode(email, kind = 'otp') {
  testEmail(email)
  let code
  await expect
    .poll(
      () => {
        const logs = docker(['logs', '--since', '10m', serviceId('auth-service')])
        const marker = `[DEV EMAIL] to=${email} | subject=`
        const phrase =
          kind === 'otp' ? 'Mã xác thực email của bạn là:' : 'Mã đặt lại mật khẩu của bạn là:'
        const matching = logs.split(marker).slice(1)
        for (const entry of matching.reverse()) {
          const match = entry.match(new RegExp(`${phrase}\\s*(\\d{6})`))
          if (match) {
            code = match[1]
            return true
          }
        }
        return false
      },
      { message: `Waiting for ${kind} email in Auth provider log`, timeout: 15_000 },
    )
    .toBe(true)
  return code
}

export async function createAccount(request, { verified = true, roles = [] } = {}) {
  const account = newAccount()
  const registered = await request.post('/api/auth/register', {
    data: { email: account.email, username: account.username, password: account.password },
  })
  expect(registered.status()).toBe(201)
  account.id = (await registered.json()).id
  if (verified) {
    expect(
      (await request.post('/api/auth/otp/request', { data: { email: account.email } })).ok(),
    ).toBe(true)
    const otp = await emailCode(account.email)
    const response = await request.post('/api/auth/otp/verify', {
      data: { email: account.email, otp },
    })
    expect(response.ok()).toBe(true)
    const session = await response.json()
    account.accessToken = session.accessToken
    expect(session.status).toBe('ACTIVE')
  }
  if (roles.length) addRoles(account.id, roles)
  expect((await request.post('/api/auth/logout')).ok()).toBe(true)
  return account
}

export function addRoles(id, roles) {
  if (roles.some((role) => !['BUYER', 'SELLER', 'ADMIN', 'SHIPPER'].includes(role))) {
    throw new Error('Unknown fixture role')
  }
  sql(
    'vmarket_auth',
    `INSERT INTO user_roles (user_id, role_id) SELECT ${accountId(id)}, id FROM roles
      WHERE name IN (${roles.map((role) => `'${role}'`).join(',')}) ON CONFLICT DO NOTHING`,
  )
}

export function suspendAccount(id) {
  sql(
    'vmarket_auth',
    `UPDATE users SET suspended_at=now(), suspended_reason='E2E' WHERE id=${accountId(id)}`,
  )
}

export function expireOtp(email) {
  sql(
    'vmarket_auth',
    `UPDATE email_otp SET expires_at=now() - interval '1 minute' WHERE email=${testEmail(email)}`,
  )
}

export function expireReset(id) {
  sql(
    'vmarket_auth',
    `UPDATE password_reset_token SET expires_at=now() - interval '1 minute' WHERE user_id=${accountId(id)}`,
  )
}

export function expireRefresh(id) {
  sql(
    'vmarket_auth',
    `UPDATE refresh_tokens SET expires_at=now() - interval '1 minute' WHERE user_id=${accountId(id)}`,
  )
}

export function allowOtpResend(email) {
  sql(
    'vmarket_auth',
    `UPDATE email_otp SET created_at=now() - interval '2 minutes' WHERE email=${testEmail(email)}`,
  )
}

export function addShop(id, status = 'PENDING') {
  if (!['PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED'].includes(status))
    throw new Error('Unknown shop status')
  const shopId = `01E2E${randomUUID().replaceAll('-', '').toUpperCase().slice(0, 21)}`
  // API IDs are opaque strings. Fixtures only fill data, never edit schema.
  sql(
    'vmarket_shop',
    `INSERT INTO shops (id, owner_id, name, name_key, contact_email, contact_phone,
      province, district, ward, street_address, status)
      VALUES ('${shopId}', ${accountId(id)}, 'Gian hàng E2E ${id}', 'e2e-${id}',
      'shop@e2e.vmarket.test', '0912345678', 'Đà Nẵng', 'Hải Châu', 'Hải Châu', '1 Hải Phòng', '${status}')`,
  )
}

export async function login(page, account, { navigate = true, identifier = account.email } = {}) {
  if (navigate) await page.goto('/login')
  await page.getByLabel('Tên tài khoản hoặc email', { exact: true }).fill(identifier)
  await page.getByLabel('Mật khẩu', { exact: true }).fill(account.password)
  const response = page.waitForResponse(
    (result) => result.url().endsWith('/api/auth/login') && result.request().method() === 'POST',
  )
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click()
  const result = await response
  await result.finished()
  // Let the UI apply the session before the caller navigates to a protected page.
  if (result.ok()) await expect(page).not.toHaveURL(/\/login(?:[?#]|$)/)
  else await expect(page.getByRole('alert')).toBeVisible()
}

export async function openAccountMenu(page) {
  await page.getByRole('button', { name: /tài khoản/i }).click()
}
