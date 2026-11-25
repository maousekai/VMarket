import { expect, test } from '@playwright/test'
import { addShop, createAccount, login } from './backend.js'

async function fillShopForm(page, name) {
  // AccountField marks required labels with an aria-hidden " *". The accessible name
  // excludes it, while getByLabel() would compare it against the visible label text.
  await page.getByRole('textbox', { name: 'Tên gian hàng', exact: true }).fill(name)
  await page
    .getByRole('textbox', { name: 'Mô tả', exact: true })
    .fill('Gian hàng thử nghiệm của PBL6-27.')
  await page
    .getByRole('textbox', { name: 'Chính sách đổi trả / vận chuyển', exact: true })
    .fill('Đổi trả trong 7 ngày nếu sản phẩm lỗi do vận chuyển.')
  await page
    .getByRole('textbox', { name: 'Email liên hệ', exact: true })
    .fill('lienhe@e2e.vmarket.test')
  await page.getByRole('textbox', { name: 'Số điện thoại liên hệ', exact: true }).fill('0912345678')
  await page
    .getByRole('combobox', { name: 'Phường / xã', exact: true })
    .selectOption('Phường Hải Châu')
  await page.getByRole('textbox', { name: 'Số nhà, tên đường', exact: true }).fill('12 Phạm Phán')
  const bank = page.getByRole('combobox', { name: 'Ngân hàng', exact: true })
  await bank.click()
  await bank.fill('vcb')
  await bank.press('Enter')
  await expect(bank).toHaveValue(/^Vietcombank/)
  await page.getByRole('textbox', { name: 'Số tài khoản', exact: true }).fill('0123456789')
}

test('buyer registers a shop, sees pending status and its status history', async ({ page }) => {
  const account = await createAccount(page.request)
  const shopName = `Gian hàng đăng ký ${account.id.slice(-8)}`
  await login(page, account)
  await page.goto('/seller')
  await expect(page).toHaveURL(/\/seller\/register/)
  await expect(page.getByText(/Đà Nẵng không còn cấp quận\/huyện/i)).toHaveCount(0)
  await expect(page.getByText(/Số di động Việt Nam bắt đầu bằng 0 hoặc \+84/i)).toHaveCount(0)

  await page.getByRole('button', { name: 'Gửi hồ sơ xét duyệt', exact: true }).click()
  const summary = page.getByRole('alert')
  await expect(summary).toContainText('Vui lòng kiểm tra các trường được đánh dấu bên dưới.')
  await expect(summary).toContainText('Vui lòng nhập tên gian hàng.')
  await expect(page.getByRole('textbox', { name: 'Tên gian hàng', exact: true })).toBeFocused()

  await fillShopForm(page, shopName)
  await page.getByRole('button', { name: 'Gửi hồ sơ xét duyệt', exact: true }).click()

  await expect(page).toHaveURL(/\/seller$/)
  await expect(page.getByRole('heading', { name: 'Gian hàng của bạn', exact: true })).toBeVisible()
  await expect(
    page.getByText('Đã gửi đơn đăng ký gian hàng. Hồ sơ đang chờ VMarket xét duyệt.', {
      exact: true,
    }),
  ).toBeVisible()
  await expect(page.getByText('Hồ sơ gian hàng đang chờ duyệt', { exact: true })).toBeVisible()
  await expect(page.getByText('Chờ duyệt', { exact: true }).first()).toBeVisible()

  await page
    .locator('.seller-status-card')
    .getByRole('link', { name: 'Cài đặt gian hàng', exact: true })
    .click()
  await expect(page).toHaveURL(/\/seller\/settings$/)
  await expect(page.getByRole('heading', { name: 'Hồ sơ gian hàng', exact: true })).toBeVisible()
  await expect(page.getByText('Nộp hồ sơ (Chờ duyệt)', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Lịch sử xét duyệt', exact: true })).toBeVisible()
})

test('backend validation details are listed instead of only a generic message', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.goto('/seller/register')
  await fillShopForm(page, `Gian hàng kiểm tra ${account.id.slice(-8)}`)

  // The browser-side checks pass; mutate the outgoing body so the real shop-service
  // rejects two fields and returns their details.
  await page.route('**/api/shops', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    const body = JSON.parse(route.request().postData())
    body.name = 'x'
    body.contactEmail = 'bad-email'
    await route.continue({ postData: JSON.stringify(body) })
  })

  await page.getByRole('button', { name: 'Gửi hồ sơ xét duyệt', exact: true }).click()

  const summary = page.getByRole('alert')
  await expect(summary).toContainText('Dữ liệu đầu vào không hợp lệ')
  await expect(summary).toContainText('Tên gian hàng từ 3 đến 100 ký tự')
  await expect(summary).toContainText('Email liên hệ không đúng định dạng')
  await expect(page.getByRole('textbox', { name: 'Tên gian hàng', exact: true })).toBeFocused()
})

test('buyer without a shop never sees the seller shell while the shop check loads', async ({
  page,
}) => {
  const account = await createAccount(page.request)
  await login(page, account)
  await page.route('**/api/shops/me', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1500))
    await route.continue()
  })

  await page.goto('/seller')

  await expect(page.getByText('Đang kiểm tra gian hàng của bạn…', { exact: true })).toBeVisible()
  await expect(page.locator('.seller-sidebar')).toHaveCount(0)
  await expect(page).toHaveURL(/\/seller\/register/)
  await expect(page.locator('.seller-sidebar')).toHaveCount(0)
})

test('public shop page shows approved shop without commerce actions', async ({ page }) => {
  const owner = await createAccount(page.request)
  const shopId = addShop(owner.id, 'ACTIVE')
  const shopName = `Gian hàng E2E ${owner.id}`

  await page.goto(`/shops/${shopId}`)

  await expect(page.getByRole('heading', { name: shopName, exact: true })).toBeVisible()
  await expect(page.getByText('Gian hàng chính thức', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('heading', { name: 'Thông tin gian hàng', exact: true }),
  ).toBeVisible()
  // Product Catalog is not part of the isolated e2e stack; the page must degrade, not fail.
  await expect(page.getByText(/danh mục sản phẩm tạm thời chưa tải được/i)).toBeVisible()
  await expect(page.getByRole('button', { name: /thêm vào giỏ|theo dõi|chat/i })).toHaveCount(0)
})

test('admin approves a pending shop and the drawer reflects the new status', async ({ page }) => {
  const owner = await createAccount(page.request)
  const shopName = `Gian hàng E2E ${owner.id}`
  addShop(owner.id)

  const admin = await createAccount(page.request, { roles: ['ADMIN'] })
  await login(page, admin)
  await page.goto('/admin/shops')
  await expect(page.getByRole('heading', { name: 'Phê duyệt gian hàng' })).toBeVisible()

  await page.getByRole('searchbox', { name: 'Tìm kiếm gian hàng' }).fill(shopName)
  await page.getByRole('button', { name: 'Tìm kiếm', exact: true }).click()
  await page.getByRole('button', { name: shopName, exact: true }).click()

  const drawer = page.locator('.modal--drawer')
  await expect(drawer.getByRole('heading', { name: 'Hồ sơ gian hàng' })).toBeVisible()
  await drawer.getByRole('button', { name: 'Duyệt gian hàng', exact: true }).click()

  const dialog = page.locator('.modal:not(.modal--drawer)')
  await expect(dialog.getByRole('heading', { name: 'Duyệt gian hàng?' })).toBeVisible()
  await dialog.getByRole('button', { name: 'Duyệt gian hàng', exact: true }).click()

  await expect(page.getByText(/Đã cập nhật trạng thái.*Đang hoạt động/)).toBeVisible()
  await expect(drawer.getByText('Đang hoạt động', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('button', { name: 'Đình chỉ gian hàng' })).toBeVisible()
})
