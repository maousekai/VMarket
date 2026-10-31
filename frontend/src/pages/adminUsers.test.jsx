// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import RouteGuard from '../components/RouteGuard'
import AdminUsersPage from './AdminUsersPage'
import { listUserActivities, lockUser, searchUsers, unlockUser } from '../services/api/userApi'

vi.mock('../services/api/userApi', async (importOriginal) => ({
  ...(await importOriginal()),
  searchUsers: vi.fn(),
  listUserActivities: vi.fn(),
  lockUser: vi.fn(),
  unlockUser: vi.fn(),
}))

const ADMIN_ID = '01JBQ9YDX7K3M8N5P2R4T6V8AA'
const BINH_ID = '01JBQ9YDX7K3M8N5P2R4T6V8W0'
const THUY_ID = '01JBQ9YDX7K3M8N5P2R4T6V8W1'

const adminSession = {
  userId: ADMIN_ID,
  username: 'admin.vmarket',
  email: 'admin@vmarket.vn',
  roles: ['ADMIN'],
  status: 'ACTIVE',
}
const binh = {
  userId: BINH_ID,
  email: 'binh.nguyen@example.com',
  username: 'binh.nguyen',
  roles: ['BUYER', 'SELLER'],
  emailVerified: true,
  status: 'ACTIVE',
  lockedAt: null,
  lockReason: null,
  lockedBy: null,
  loginLockedUntil: null,
  fullName: 'Nguyễn Văn Bình',
  phone: '0905842199',
  avatarUrl: null,
  createdAt: '2023-01-12T03:00:00Z',
}
const thuy = {
  ...binh,
  userId: THUY_ID,
  email: 'thuy.tran@example.com',
  username: 'thuy.tran',
  roles: ['BUYER'],
  status: 'LOCKED',
  lockedAt: '2026-10-01T03:00:00Z',
  lockReason: 'Spam đánh giá',
  lockedBy: ADMIN_ID,
  fullName: null,
  phone: null,
  createdAt: '2024-04-20T03:00:00Z',
}
const page = (items, extra = {}) => ({
  items,
  page: 0,
  size: 10,
  totalElements: items.length,
  totalPages: 1,
  ...extra,
})
const apiFailure = (status, code, message) => ({
  response: { status, data: { error: { code, message } } },
})

function auth(session = adminSession) {
  return { session, bootstrapState: 'ready', userProfile: null, profileState: 'idle' }
}

function view(ui = <AdminUsersPage />, value = auth()) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>{ui}</MemoryRouter>
    </AuthContext.Provider>,
  )
}

const click = (name, scope = screen) => fireEvent.click(scope.getByRole('button', { name }))
const nameButton = (name) => screen.findByRole('button', { name })
const drawer = () => screen.getByRole('dialog', { name: 'Hồ sơ người dùng chi tiết' })
const openDrawer = async (name) => {
  fireEvent.click(await nameButton(name))
  await screen.findByRole('dialog', { name: 'Hồ sơ người dùng chi tiết' })
}

beforeEach(() => {
  vi.resetAllMocks()
  searchUsers.mockResolvedValue(page([binh, thuy]))
  listUserActivities.mockResolvedValue(page([]))
})
afterEach(cleanup)

describe('Admin user list', () => {
  it('lists users with roles, status and the total', async () => {
    view()
    const first = (await nameButton('Nguyễn Văn Bình')).closest('tr')
    const row = within(first)
    expect(row.getByText('binh.nguyen@example.com')).toBeTruthy()
    expect(row.getByText('0905842199')).toBeTruthy()
    expect(row.getByText('Buyer')).toBeTruthy()
    expect(row.getByText('Seller')).toBeTruthy()
    expect(row.getByText('Đang hoạt động')).toBeTruthy()
    expect(row.getByText('12/01/2023')).toBeTruthy()
    const second = within(screen.getByRole('button', { name: 'thuy.tran' }).closest('tr'))
    expect(second.getByText('Đã khóa')).toBeTruthy()
    expect(screen.getByText('Hiển thị 1–2 trong số 2 người dùng')).toBeTruthy()
    expect(searchUsers).toHaveBeenCalledWith({ q: '', status: '', page: 0, size: 10 })
  })

  it('shows a temporary login lock next to the status', async () => {
    const future = new Date(Date.now() + 3_600_000).toISOString()
    searchUsers.mockResolvedValue(page([{ ...binh, loginLockedUntil: future }]))
    view()
    const row = within((await nameButton('Nguyễn Văn Bình')).closest('tr'))
    expect(row.getByText('Đang hoạt động')).toBeTruthy()
    expect(row.getByText('Khóa tạm do nhập sai mật khẩu')).toBeTruthy()
  })

  it('searches with the trimmed keyword and can clear it', async () => {
    view()
    await nameButton('Nguyễn Văn Bình')
    fireEvent.change(screen.getByLabelText('Tìm kiếm người dùng'), {
      target: { value: '  binh  ' },
    })
    click('Tìm kiếm')
    await waitFor(() =>
      expect(searchUsers).toHaveBeenLastCalledWith({ q: 'binh', status: '', page: 0, size: 10 }),
    )
    await nameButton('Nguyễn Văn Bình')
    click('Xóa tìm kiếm')
    await waitFor(() =>
      expect(searchUsers).toHaveBeenLastCalledWith({ q: '', status: '', page: 0, size: 10 }),
    )
    expect(screen.getByLabelText('Tìm kiếm người dùng').value).toBe('')
  })

  it('rejects a keyword longer than 100 characters without calling the API', async () => {
    view()
    await nameButton('Nguyễn Văn Bình')
    fireEvent.change(screen.getByLabelText('Tìm kiếm người dùng'), {
      target: { value: 'a'.repeat(101) },
    })
    click('Tìm kiếm')
    expect(screen.getByText('Từ khoá tối đa 100 ký tự.')).toBeTruthy()
    const input = screen.getByLabelText('Tìm kiếm người dùng')
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(input.getAttribute('aria-describedby')).toBe('admin-users-search-error')
    expect(document.getElementById('admin-users-search-error').textContent).toBe(
      'Từ khoá tối đa 100 ký tự.',
    )
    expect(searchUsers).toHaveBeenCalledTimes(1)
  })

  it('filters by status and goes back to the first page', async () => {
    view()
    await nameButton('Nguyễn Văn Bình')
    expect(screen.getByRole('button', { name: 'Tất cả' }).getAttribute('aria-pressed')).toBe('true')
    click('Đã khóa')
    await waitFor(() =>
      expect(searchUsers).toHaveBeenLastCalledWith({ q: '', status: 'LOCKED', page: 0, size: 10 }),
    )
    await nameButton('Nguyễn Văn Bình')
    expect(screen.getByRole('button', { name: 'Đã khóa' }).getAttribute('aria-pressed')).toBe(
      'true',
    )
  })

  it('pages through the results', async () => {
    searchUsers
      .mockResolvedValueOnce(page([binh, thuy], { totalElements: 25, totalPages: 3 }))
      .mockResolvedValueOnce(page([thuy], { page: 1, totalElements: 25, totalPages: 3 }))
    view()
    await nameButton('Nguyễn Văn Bình')
    expect(screen.getByText('Hiển thị 1–2 trong số 25 người dùng')).toBeTruthy()
    expect(screen.getByText('Trang 1 / 3')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Trước' }).disabled).toBe(true)
    click('Sau')
    await waitFor(() =>
      expect(searchUsers).toHaveBeenLastCalledWith({ q: '', status: '', page: 1, size: 10 }),
    )
    expect(await screen.findByText('Trang 2 / 3')).toBeTruthy()
    expect(screen.getByText('Hiển thị 11–11 trong số 25 người dùng')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Trước' }).disabled).toBe(false)
  })

  it('keeps the current table visible while the next page is loading', async () => {
    let resolveSearch
    searchUsers
      .mockResolvedValueOnce(page([binh], { totalElements: 11, totalPages: 2 }))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveSearch = resolve
          }),
      )
    view()
    await nameButton('Nguyễn Văn Bình')
    click('Sau')
    await waitFor(() => expect(searchUsers).toHaveBeenCalledTimes(2))
    const table = screen.getByRole('table')
    expect(screen.getByText('Nguyễn Văn Bình')).toBeTruthy()
    expect(table.closest('.admin-users__table-wrap').getAttribute('aria-busy')).toBe('true')
    expect(screen.getByRole('button', { name: 'Sau' }).disabled).toBe(true)
    resolveSearch(page([thuy], { page: 1, totalElements: 11, totalPages: 2 }))
    expect(await screen.findByText('Trang 2 / 2')).toBeTruthy()
    expect(await nameButton('thuy.tran')).toBeTruthy()
  })

  it('shows an empty state', async () => {
    searchUsers.mockResolvedValue(page([]))
    view()
    expect(await screen.findByText('Không tìm thấy người dùng phù hợp.')).toBeTruthy()
  })

  it('falls back to the last page when the current page becomes empty', async () => {
    searchUsers
      .mockResolvedValueOnce(page([binh], { totalElements: 11, totalPages: 2 }))
      .mockResolvedValueOnce(page([], { page: 1, totalElements: 10, totalPages: 1 }))
      .mockResolvedValueOnce(page([binh], { page: 0, totalElements: 10, totalPages: 1 }))
    view()
    await nameButton('Nguyễn Văn Bình')
    click('Sau')
    await waitFor(() =>
      expect(searchUsers).toHaveBeenLastCalledWith({ q: '', status: '', page: 0, size: 10 }),
    )
    expect(await nameButton('Nguyễn Văn Bình')).toBeTruthy()
    expect(screen.getByText('Trang 1 / 1')).toBeTruthy()
  })

  it('shows the server message for a too broad search and recovers on retry', async () => {
    searchUsers
      .mockRejectedValueOnce(apiFailure(400, 'SEARCH_TOO_BROAD', 'Từ khoá quá rộng, hãy thu hẹp.'))
      .mockResolvedValueOnce(page([binh]))
    view()
    expect((await screen.findByRole('alert')).textContent).toBe('Từ khoá quá rộng, hãy thu hẹp.')
    click('Thử lại')
    expect(await nameButton('Nguyễn Văn Bình')).toBeTruthy()
  })
})

describe('Admin user detail', () => {
  const activities = [
    {
      action: 'SUSPENDED',
      actorId: ADMIN_ID,
      actorUsername: 'admin.vmarket',
      reason: 'Spam đánh giá',
      createdAt: '2026-10-01T03:00:00Z',
    },
    {
      action: 'PASSWORD_CHANGED',
      actorId: null,
      actorUsername: null,
      reason: null,
      createdAt: '2026-09-01T03:00:00Z',
    },
  ]

  it('opens the profile with contact details and recent activity', async () => {
    listUserActivities.mockResolvedValue(page(activities))
    view()
    await openDrawer('Nguyễn Văn Bình')
    const panel = within(drawer())
    expect(panel.getByText('binh.nguyen@example.com (đã xác thực)')).toBeTruthy()
    expect(panel.getByText('0905842199')).toBeTruthy()
    expect(panel.getByText('12/01/2023')).toBeTruthy()
    expect(await panel.findByText('Admin admin.vmarket đã khóa tài khoản')).toBeTruthy()
    expect(panel.getByText('Lý do: Spam đánh giá')).toBeTruthy()
    expect(panel.getByText('Người dùng đổi mật khẩu')).toBeTruthy()
    expect(listUserActivities).toHaveBeenCalledWith(BINH_ID, { page: 0, size: 10 })
  })

  it('loads more activity when there are further pages', async () => {
    listUserActivities
      .mockResolvedValueOnce(page(activities, { totalPages: 2 }))
      .mockResolvedValueOnce(
        page([{ ...activities[1], action: 'PASSWORD_RESET' }], { page: 1, totalPages: 2 }),
      )
    view()
    await openDrawer('Nguyễn Văn Bình')
    const panel = within(drawer())
    await panel.findByText('Người dùng đổi mật khẩu')
    fireEvent.click(panel.getByRole('button', { name: 'Xem thêm' }))
    expect(await panel.findByText('Đặt lại mật khẩu qua email')).toBeTruthy()
    expect(listUserActivities).toHaveBeenLastCalledWith(BINH_ID, { page: 1, size: 10 })
    expect(panel.queryByRole('button', { name: 'Xem thêm' })).toBeNull()
  })

  it('keeps the profile usable when the activity history fails to load', async () => {
    listUserActivities.mockRejectedValue(
      apiFailure(503, 'AUTH_SERVICE_UNAVAILABLE', 'Chưa tải được.'),
    )
    view()
    await openDrawer('Nguyễn Văn Bình')
    expect((await within(drawer()).findByRole('alert')).textContent).toBe('Chưa tải được.')
    expect(within(drawer()).getByText('0905842199')).toBeTruthy()
  })

  it('closes with Escape or Đóng', async () => {
    view()
    await openDrawer('Nguyễn Văn Bình')
    fireEvent.keyDown(drawer(), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    await openDrawer('Nguyễn Văn Bình')
    click('Đóng', within(drawer()))
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('Locking a user', () => {
  const lockDialog = () =>
    screen.findByRole('dialog', { name: 'Xác nhận khóa tài khoản người dùng' })
  const locked = {
    ...binh,
    status: 'LOCKED',
    lockedAt: '2026-10-22T03:00:00Z',
    lockReason: 'Spam đánh giá',
    lockedBy: ADMIN_ID,
  }

  async function openLock() {
    await openDrawer('Nguyễn Văn Bình')
    click('Khóa tài khoản', within(drawer()))
    const dialog = within(await lockDialog())
    expect(document.activeElement).toBe(dialog.getByLabelText(/Lý do khóa/))
    return dialog
  }

  it('requires a reason, then locks, refreshes the list and shows the unlock action', async () => {
    searchUsers.mockResolvedValueOnce(page([binh])).mockResolvedValue(page([locked]))
    lockUser.mockResolvedValue(locked)
    view()
    const dialog = await openLock()
    click('Khóa tài khoản', dialog)
    expect(lockUser).not.toHaveBeenCalled()
    expect(dialog.getByText(/Vui lòng nhập lý do/)).toBeTruthy()
    fireEvent.change(dialog.getByLabelText(/Lý do khóa/), { target: { value: ' Spam đánh giá ' } })
    click('Khóa tài khoản', dialog)
    await waitFor(() => expect(lockUser).toHaveBeenCalledTimes(1))
    expect(lockUser.mock.calls[0].slice(0, 2)).toEqual([BINH_ID, 'Spam đánh giá'])
    expect(typeof lockUser.mock.calls[0][2]).toBe('string')
    expect(await screen.findByText('Đã khóa tài khoản của Nguyễn Văn Bình.')).toBeTruthy()
    expect(screen.queryByRole('dialog', { name: 'Xác nhận khóa tài khoản người dùng' })).toBeNull()
    expect(within(drawer()).getByRole('button', { name: 'Mở khóa tài khoản' })).toBeTruthy()
    expect(within(drawer()).getByText(/Tài khoản bị khóa lúc/)).toBeTruthy()
    await waitFor(() => expect(searchUsers).toHaveBeenCalledTimes(2))
  })

  it('shows the server error, keeps the dialog and reuses the key for the same reason', async () => {
    lockUser.mockRejectedValue(apiFailure(503, 'AUTH_SERVICE_UNAVAILABLE', 'Máy chủ bận.'))
    view()
    const dialog = await openLock()
    fireEvent.change(dialog.getByLabelText(/Lý do khóa/), { target: { value: 'Spam' } })
    click('Khóa tài khoản', dialog)
    expect((await dialog.findByRole('alert')).textContent).toContain('Máy chủ bận.')
    click('Khóa tài khoản', dialog)
    await waitFor(() => expect(lockUser).toHaveBeenCalledTimes(2))
    expect(lockUser.mock.calls[1][2]).toBe(lockUser.mock.calls[0][2])
    await dialog.findByRole('alert')
    fireEvent.change(dialog.getByLabelText(/Lý do khóa/), { target: { value: 'Spam nặng' } })
    click('Khóa tài khoản', dialog)
    await waitFor(() => expect(lockUser).toHaveBeenCalledTimes(3))
    expect(lockUser.mock.calls[2][2]).not.toBe(lockUser.mock.calls[0][2])
  })

  it('cancels without calling the API', async () => {
    view()
    const dialog = await openLock()
    click('Hủy', dialog)
    expect(screen.queryByRole('dialog', { name: 'Xác nhận khóa tài khoản người dùng' })).toBeNull()
    expect(drawer()).toBeTruthy()
    expect(lockUser).not.toHaveBeenCalled()
  })

  it('does not let an admin lock their own account', async () => {
    view(<AdminUsersPage />, auth({ ...adminSession, userId: BINH_ID }))
    await openDrawer('Nguyễn Văn Bình')
    expect(within(drawer()).getByRole('button', { name: 'Khóa tài khoản' }).disabled).toBe(true)
    expect(within(drawer()).getByText('Bạn không thể khóa chính tài khoản của mình.')).toBeTruthy()
  })
})

describe('Unlocking a user', () => {
  const unlocked = { ...thuy, status: 'ACTIVE', lockedAt: null, lockReason: null, lockedBy: null }

  it('shows the lock reason and unlocks after confirmation', async () => {
    searchUsers.mockResolvedValueOnce(page([thuy])).mockResolvedValue(page([unlocked]))
    unlockUser.mockResolvedValue(unlocked)
    view()
    await openDrawer('thuy.tran')
    expect(within(drawer()).getByText(/Lý do: Spam đánh giá/)).toBeTruthy()
    click('Mở khóa tài khoản', within(drawer()))
    const rawDialog = await screen.findByRole('dialog', { name: 'Mở khóa tài khoản?' })
    const dialog = within(rawDialog)
    expect(document.activeElement).toBe(dialog.getByRole('button', { name: 'Hủy' }))
    click('Mở khóa', dialog)
    await waitFor(() => expect(unlockUser).toHaveBeenCalledTimes(1))
    expect(unlockUser.mock.calls[0][0]).toBe(THUY_ID)
    expect(await screen.findByText('Đã mở khóa tài khoản của thuy.tran.')).toBeTruthy()
    expect(within(drawer()).getByRole('button', { name: 'Khóa tài khoản' })).toBeTruthy()
  })

  it('keeps the confirmation open on failure and retries with the same key', async () => {
    unlockUser
      .mockRejectedValueOnce(apiFailure(503, 'AUTH_SERVICE_UNAVAILABLE', 'Máy chủ bận.'))
      .mockResolvedValueOnce(unlocked)
    searchUsers.mockResolvedValue(page([thuy]))
    view()
    await openDrawer('thuy.tran')
    click('Mở khóa tài khoản', within(drawer()))
    const dialog = within(await screen.findByRole('dialog', { name: 'Mở khóa tài khoản?' }))
    click('Mở khóa', dialog)
    expect((await dialog.findByRole('alert')).textContent).toContain('Máy chủ bận.')
    click('Mở khóa', dialog)
    await waitFor(() => expect(unlockUser).toHaveBeenCalledTimes(2))
    expect(unlockUser.mock.calls[1][1]).toBe(unlockUser.mock.calls[0][1])
  })

  it('also offers the unlock for a temporary login lock', async () => {
    const future = new Date(Date.now() + 3_600_000).toISOString()
    searchUsers.mockResolvedValue(page([{ ...binh, loginLockedUntil: future }]))
    view()
    await openDrawer('Nguyễn Văn Bình')
    expect(within(drawer()).getByRole('button', { name: 'Mở khóa tài khoản' })).toBeTruthy()
    expect(within(drawer()).queryByRole('button', { name: 'Khóa tài khoản' })).toBeNull()
    expect(within(drawer()).getByText(/Tạm khóa đăng nhập đến/)).toBeTruthy()
  })
})

describe('Access to the admin area', () => {
  const guarded = (session) =>
    view(
      <Routes>
        <Route element={<RouteGuard roles={['ADMIN']} />}>
          <Route path="/" element={<AdminUsersPage />} />
        </Route>
      </Routes>,
      auth(session),
    )

  it('never mounts the user list for a non-admin account', () => {
    guarded({ ...adminSession, roles: ['BUYER'] })
    expect(screen.getByText('Bạn chưa có quyền truy cập')).toBeTruthy()
    expect(searchUsers).not.toHaveBeenCalled()
  })

  it('mounts the user list for an admin account', async () => {
    guarded(adminSession)
    expect(await nameButton('Nguyễn Văn Bình')).toBeTruthy()
  })
})
