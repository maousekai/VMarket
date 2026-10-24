import { expect, test } from '@playwright/test'
import { createAccount, login } from './backend.js'

const SEEDED_ADMIN = { email: 'admin@vmarket.local', password: 'Admin@12345' }

async function openProfile(page, account) {
  await login(page, account)
  await page.goto('/profile')
  await expect(page.getByLabel('Họ và tên', { exact: true })).toBeVisible()
}

async function fillAddress(page, { recipient, phone = '0912345678', street, ward }) {
  await page.getByLabel(/Người nhận/).fill(recipient)
  await page.getByLabel(/Số điện thoại/).fill(phone)
  await page.getByLabel(/Số nhà, tên đường/).fill(street)
  await page.getByLabel(/Phường \/ xã/).selectOption(ward)
}

const addressCard = (page, recipient) =>
  page.getByRole('listitem', { name: `Địa chỉ của ${recipient}` })

test('profile edits persist, including birth date and gender', async ({ page }) => {
  const account = await createAccount(page.request)
  await openProfile(page, account)
  await page.getByLabel('Họ và tên', { exact: true }).fill('Trần Thu Hà')
  await page.getByLabel('Số điện thoại', { exact: true }).fill('0987654321')
  await page.getByLabel('Ngày sinh', { exact: true }).fill('2001-02-03')
  await page.getByLabel('Giới tính', { exact: true }).selectOption('FEMALE')
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  await expect(page.getByRole('status')).toContainText(/đã.*lưu|thành công/i)
  await page.reload()
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue('Trần Thu Hà')
  await expect(page.getByLabel('Số điện thoại', { exact: true })).toHaveValue('0987654321')
  await expect(page.getByLabel('Ngày sinh', { exact: true })).toHaveValue('2001-02-03')
  await expect(page.getByLabel('Giới tính', { exact: true })).toHaveValue('FEMALE')
})

test('profile form rejects a future birth date without calling the server', async ({ page }) => {
  const account = await createAccount(page.request)
  await openProfile(page, account)
  let writes = 0
  await page.route('**/api/users/me', (route) => {
    if (route.request().method() === 'PUT') writes += 1
    return route.continue()
  })
  await page.getByLabel('Ngày sinh', { exact: true }).fill('2999-01-01')
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  await expect(page.getByLabel('Ngày sinh', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  expect(writes).toBe(0)
})

test('avatar is changed from the button under the picture with an image URL, and removed', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await openProfile(page, account)
  await expect(page.getByLabel(/Đường dẫn ảnh/)).toHaveCount(0)
  await page.getByRole('button', { name: 'Đổi ảnh đại diện', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Đổi ảnh đại diện' })
  await dialog.getByLabel('Đường dẫn ảnh').fill('ftp://example.com/a.png')
  await dialog.getByRole('button', { name: 'Lưu ảnh', exact: true }).click()
  await expect(dialog.getByLabel('Đường dẫn ảnh')).toHaveAttribute('aria-invalid', 'true')
  await dialog.getByLabel('Đường dẫn ảnh').fill('https://example.com/avatar.png')
  await dialog.getByRole('button', { name: 'Lưu ảnh', exact: true }).click()
  await expect(dialog).toBeHidden()
  await page.reload()
  await page.getByRole('button', { name: 'Đổi ảnh đại diện', exact: true }).click()
  await expect(dialog.getByLabel('Đường dẫn ảnh')).toHaveValue('https://example.com/avatar.png')
  await dialog.getByRole('button', { name: 'Xóa ảnh', exact: true }).click()
  await expect(dialog).toBeHidden()
  await page.reload()
  await page.getByRole('button', { name: 'Đổi ảnh đại diện', exact: true }).click()
  await expect(dialog.getByLabel('Đường dẫn ảnh')).toHaveValue('')
})

test('address book: add, default switching, edit and delete with the real User service', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/profile/addresses')
  await expect(page.getByText('Chưa có địa chỉ giao hàng')).toBeVisible()

  // First address: becomes the default; Da Nang has no district so none is sent.
  await page.getByRole('button', { name: 'Thêm địa chỉ đầu tiên', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Thêm địa chỉ nhận hàng' })
  await expect(dialog.getByLabel(/Tỉnh \/ thành/)).toBeDisabled()
  await expect(dialog.getByLabel(/Quận/)).toHaveCount(0)
  await fillAddress(page, {
    recipient: 'Nguyễn Minh Tuấn',
    street: '120 Hoàng Diệu',
    ward: 'Phường Hải Châu',
  })
  const created = page.waitForRequest(
    (request) => request.url().endsWith('/api/users/me/addresses') && request.method() === 'POST',
  )
  await dialog.getByRole('button', { name: 'Lưu địa chỉ', exact: true }).click()
  expect((await created).postDataJSON()).not.toHaveProperty('district')
  const first = addressCard(page, 'Nguyễn Minh Tuấn')
  await expect(first).toContainText('Mặc định')
  await expect(first).toContainText('120 Hoàng Diệu, Phường Hải Châu, Thành phố Đà Nẵng')

  // Second address made the default at creation time.
  await page.getByRole('button', { name: 'Thêm địa chỉ mới', exact: true }).click()
  await fillAddress(page, {
    recipient: 'Trần Thu Hà',
    street: 'Tầng 5 Software Park',
    ward: 'Xã Hòa Vang',
  })
  await dialog.getByLabel(/Địa chỉ mặc định/).selectOption('yes')
  await dialog.getByRole('button', { name: 'Lưu địa chỉ', exact: true }).click()
  const second = addressCard(page, 'Trần Thu Hà')
  await expect(second).toContainText('Mặc định')
  await expect(first).not.toContainText('Mặc định')

  // Switch the default back from the list.
  await first.getByRole('button', { name: 'Thiết lập mặc định', exact: true }).click()
  await expect(first).toContainText('Mặc định')
  await expect(second).not.toContainText('Mặc định')

  // Edit.
  await second.getByRole('button', { name: 'Chỉnh sửa', exact: true }).click()
  await page.getByLabel(/Số nhà, tên đường/).fill('12 Lê Duẩn')
  await page
    .getByRole('dialog', { name: 'Chỉnh sửa địa chỉ' })
    .getByRole('button', { name: 'Lưu địa chỉ', exact: true })
    .click()
  await expect(second).toContainText('12 Lê Duẩn, Xã Hòa Vang')

  // Delete the default: the remaining one is promoted.
  await first.getByRole('button', { name: 'Xóa', exact: true }).click()
  const confirm = page.getByRole('dialog', { name: 'Xóa địa chỉ?' })
  await expect(confirm).toContainText('sẽ được đặt làm mặc định')
  await confirm.getByRole('button', { name: 'Xóa địa chỉ', exact: true }).click()
  await expect(first).toHaveCount(0)
  await expect(second).toContainText('Mặc định')
  await page.reload()
  await expect(addressCard(page, 'Trần Thu Hà')).toContainText('Mặc định')
})

test('address form validates locally: ward is required and the phone has no spaces', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/profile/addresses')
  await page.getByRole('button', { name: 'Thêm địa chỉ đầu tiên', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Thêm địa chỉ nhận hàng' })
  await fillAddress(page, {
    recipient: 'An',
    phone: '0912 345 678',
    street: '1 Hải Phòng',
    ward: '',
  })
  await dialog.getByRole('button', { name: 'Lưu địa chỉ', exact: true }).click()
  await expect(dialog.getByLabel(/Phường \/ xã/)).toHaveAttribute('aria-invalid', 'true')
  await expect(dialog.getByLabel(/Số điện thoại/)).toHaveAttribute('aria-invalid', 'true')
  await expect(page.getByText('Chưa có địa chỉ giao hàng')).toBeVisible()
})

test('password change signs out everywhere; only the new password works afterwards', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  const newPassword = 'E2eNewPass2!'
  await login(page, account)
  await page.goto('/profile/password')
  await page.getByLabel(/^Mật khẩu hiện tại/).fill('Sai-mat-khau-1!')
  await page.getByLabel(/^Mật khẩu mới/).fill(newPassword)
  await page.getByLabel(/^Xác nhận mật khẩu mới/).fill(newPassword)
  await page.getByRole('button', { name: 'Đổi mật khẩu', exact: true }).click()
  await expect(page.getByLabel(/^Mật khẩu hiện tại/)).toHaveAttribute('aria-invalid', 'true')
  await expect(page).toHaveURL(/\/profile\/password/)

  await page.getByLabel(/^Mật khẩu hiện tại/).fill(account.password)
  await page.getByRole('button', { name: 'Đổi mật khẩu', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByText(/Đã đổi mật khẩu/)).toBeVisible()

  await login(page, account, { navigate: false })
  await expect(page.getByRole('alert')).toBeVisible()
  await login(page, { ...account, password: newPassword }, { navigate: false })
  await expect(page).not.toHaveURL(/\/login/)
})

test('password fields have exactly one show/hide button each and it toggles visibility', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/profile/password')
  const toggles = page.locator('button.password-toggle')
  await expect(toggles).toHaveCount(3)
  const current = page.getByLabel(/^Mật khẩu hiện tại/)
  await expect(current).toHaveAttribute('type', 'password')
  await toggles.first().click()
  await expect(current).toHaveAttribute('type', 'text')
  await toggles.first().click()
  await expect(current).toHaveAttribute('type', 'password')
  await page.context().clearCookies()
  await page.goto('/login')
  await expect(page.locator('button.password-toggle')).toHaveCount(1)
})

test('the seeded dev admin can sign in and reach the admin console', async ({ page }) => {
  await login(page, SEEDED_ADMIN, { email: SEEDED_ADMIN.email })
  await page.goto('/admin/users')
  await expect(
    page.getByRole('heading', { name: 'Quản lý người dùng toàn sàn', exact: true }),
  ).toBeVisible()
})

test('admin sidebar navigates between sections and leaves unbuilt ones empty', async ({ page }) => {
  const admin = await createAccount(page.request, { roles: ['ADMIN'] })
  await login(page, admin)
  await page.goto('/admin')
  const nav = page.getByRole('navigation', { name: 'Điều hướng quản trị' })
  await expect(nav.getByRole('link', { name: 'Tổng quan' })).toHaveAttribute('aria-current', 'page')
  await expect(page.getByLabel('Tìm mã đơn')).toHaveCount(0)
  await nav.getByRole('link', { name: 'Giám sát đơn hàng' }).click()
  await expect(page).toHaveURL(/\/admin\/orders$/)
  await expect(page.getByRole('heading', { name: 'Giám sát đơn hàng', exact: true })).toBeVisible()
  await expect(page.getByText('Chức năng này đang được phát triển.')).toBeVisible()
  await nav.getByRole('link', { name: 'Quản lý người dùng' }).click()
  await expect(page).toHaveURL(/\/admin\/users$/)
  await expect(nav.getByRole('link', { name: 'Quản lý người dùng' })).toHaveAttribute(
    'aria-current',
    'page',
  )
})

test('admin console collapses its sidebar into a menu on a narrow screen', async ({ page }) => {
  const admin = await createAccount(page.request, { roles: ['ADMIN'] })
  await login(page, admin)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/admin')
  const nav = page.getByRole('navigation', { name: 'Điều hướng quản trị' })
  await expect(nav).not.toBeInViewport()
  await page.getByRole('button', { name: 'Mở menu', exact: true }).click()
  await expect(nav).toBeInViewport()
  await nav.getByRole('link', { name: 'Quản lý danh mục' }).click()
  await expect(page).toHaveURL(/\/admin\/categories$/)
  await expect(nav).not.toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
})

test('admin account menu signs out', async ({ page }) => {
  const admin = await createAccount(page.request, { roles: ['ADMIN'] })
  await login(page, admin)
  await page.goto('/admin')
  await page.getByRole('button', { name: /^Tài khoản của/ }).click()
  await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/login/)
})

test('admin finds a user, locks them with a reason, then unlocks them', async ({
  page,
  browser,
}, testInfo) => {
  const admin = await createAccount(page.request, { roles: ['ADMIN'] })
  const target = await createAccount(page.request)
  const userContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL })
  const userPage = await userContext.newPage()
  try {
    // The user opens the profile page once so that their profile exists.
    await openProfile(userPage, target)

    await login(page, admin)
    await page.goto('/admin/users')
    await page.getByLabel('Tìm kiếm người dùng').fill(target.email)
    await page.getByRole('button', { name: 'Tìm kiếm', exact: true }).click()
    await page.getByRole('button', { name: target.username, exact: true }).click()
    const drawer = page.getByRole('dialog', { name: 'Hồ sơ người dùng chi tiết' })
    await expect(drawer).toContainText(target.email)

    await drawer.getByRole('button', { name: 'Khóa tài khoản', exact: true }).click()
    const lock = page.getByRole('dialog', { name: 'Xác nhận khóa tài khoản người dùng' })
    await lock.getByRole('button', { name: 'Khóa tài khoản', exact: true }).click()
    await expect(lock.getByLabel(/Lý do khóa/)).toHaveAttribute('aria-invalid', 'true')
    await lock.getByLabel(/Lý do khóa/).fill('Vi phạm chính sách nền tảng (E2E)')
    await lock.getByRole('button', { name: 'Khóa tài khoản', exact: true }).click()
    await expect(lock).toBeHidden()
    await expect(page.getByRole('status').filter({ hasText: 'Đã khóa tài khoản' })).toBeVisible()
    await expect(drawer).toContainText('Vi phạm chính sách nền tảng (E2E)')
    await expect(
      drawer.getByRole('button', { name: 'Mở khóa tài khoản', exact: true }),
    ).toBeVisible()

    // A locked user cannot sign in again.
    await userPage.context().clearCookies()
    await login(userPage, target)
    await expect(userPage).toHaveURL(/\/login/)

    await drawer.getByRole('button', { name: 'Mở khóa tài khoản', exact: true }).click()
    const unlock = page.getByRole('dialog', { name: 'Mở khóa tài khoản?' })
    await unlock.getByRole('button', { name: 'Mở khóa', exact: true }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Đã mở khóa tài khoản' })).toBeVisible()
    await expect(drawer.getByRole('button', { name: 'Khóa tài khoản', exact: true })).toBeVisible()

    await login(userPage, target, { navigate: false })
    await expect(userPage).not.toHaveURL(/\/login/)
  } finally {
    await userContext.close()
  }
})

test('an admin cannot lock their own account and a non-admin cannot open the console', async ({
  page,
  browser,
}, testInfo) => {
  const admin = await createAccount(page.request, { roles: ['ADMIN'] })
  await login(page, admin)
  await page.goto('/admin/users')
  await page.getByLabel('Tìm kiếm người dùng').fill(admin.email)
  await page.getByRole('button', { name: 'Tìm kiếm', exact: true }).click()
  await page.getByRole('button', { name: admin.username, exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'Hồ sơ người dùng chi tiết' })
  await expect(drawer.getByRole('button', { name: 'Khóa tài khoản', exact: true })).toBeDisabled()

  const buyer = await createAccount(page.request)
  const buyerContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL })
  try {
    const buyerPage = await buyerContext.newPage()
    await login(buyerPage, buyer)
    await buyerPage.goto('/admin/users')
    await expect(buyerPage.getByText('Bạn chưa có quyền truy cập')).toBeVisible()
    await expect(buyerPage.getByRole('navigation', { name: 'Điều hướng quản trị' })).toHaveCount(0)
  } finally {
    await buyerContext.close()
  }
})
