// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import AddressBookPage from './AddressBookPage'
import ChangePasswordPage from './ChangePasswordPage'
import ProfilePage from './ProfilePage'
import {
  changeMyPassword,
  createAddress,
  deleteAddress,
  listMyAddresses,
  setDefaultAddress,
  updateAddress,
} from '../services/api/userApi'

vi.mock('../services/api/userApi', async (importOriginal) => ({
  ...(await importOriginal()),
  listMyAddresses: vi.fn(),
  createAddress: vi.fn(),
  updateAddress: vi.fn(),
  deleteAddress: vi.fn(),
  setDefaultAddress: vi.fn(),
  changeMyPassword: vi.fn(),
}))

const session = {
  userId: 'buyer-one',
  username: 'buyer',
  email: 'buyer@example.com',
  roles: ['BUYER'],
  status: 'ACTIVE',
}
const profile = {
  userId: 'buyer-one',
  fullName: 'Nguyễn Minh Tuấn',
  phone: '0905123456',
  avatarUrl: null,
  dateOfBirth: '1998-08-12',
  gender: 'UNDISCLOSED',
}
const home = {
  id: 'a1',
  recipientName: 'Nguyễn Minh Tuấn',
  phone: '0905123456',
  province: 'Đà Nẵng',
  district: 'Hải Châu',
  ward: 'Thạch Thang',
  streetAddress: '120 Hoàng Diệu',
  note: null,
  isDefault: true,
}
const office = {
  id: 'a2',
  recipientName: 'Trần Thu Hà',
  phone: '0912345678',
  province: 'Đà Nẵng',
  district: 'Hải Châu',
  ward: 'Hòa Cường',
  streetAddress: 'Tầng 5 Software Park',
  note: 'Gọi trước khi giao',
  isDefault: false,
}
const apiFailure = (status, code, message) => ({
  response: { status, data: { error: { code, message } } },
})

function auth(overrides = {}) {
  return {
    session,
    bootstrapState: 'ready',
    userProfile: profile,
    profileState: 'ready',
    loadProfile: vi.fn(),
    saveProfile: vi.fn().mockResolvedValue(profile),
    logout: vi.fn().mockResolvedValue(undefined),
    invalidateSession: vi.fn(),
    ...overrides,
  }
}

function LoginTarget() {
  const location = useLocation()
  return <p>login: {location.state?.message}</p>
}

function view(page, value = auth()) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>
        <Routes>
          <Route path="/login" element={<LoginTarget />} />
          <Route path="*" element={page} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

const fill = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const click = (name) => fireEvent.click(screen.getByRole('button', { name }))
// Buttons that only exist once the address list has loaded.
const clickWhenReady = async (name) => fireEvent.click(await screen.findByRole('button', { name }))

beforeEach(() => {
  vi.resetAllMocks()
  listMyAddresses.mockResolvedValue([])
})
afterEach(cleanup)

describe('Profile page additions', () => {
  it('shows the stored date of birth and gender and links to the other account pages', () => {
    view(<ProfilePage />)
    expect(screen.getByLabelText('Ngày sinh').value).toBe('1998-08-12')
    expect(screen.getByLabelText('Giới tính').value).toBe('UNDISCLOSED')
    expect(screen.getByRole('link', { name: 'Hồ sơ cá nhân' }).getAttribute('aria-current')).toBe(
      'page',
    )
    expect(screen.getByRole('link', { name: 'Sổ địa chỉ' }).getAttribute('href')).toBe(
      '/profile/addresses',
    )
    expect(screen.getByRole('link', { name: 'Đổi mật khẩu' }).getAttribute('href')).toBe(
      '/profile/password',
    )
  })

  it('sends the edited date of birth and gender to the profile API', async () => {
    const value = auth()
    view(<ProfilePage />, value)
    fill('Ngày sinh', '2001-02-03')
    fill('Giới tính', 'FEMALE')
    click('Lưu thay đổi')
    await waitFor(() => expect(value.saveProfile).toHaveBeenCalledTimes(1))
    expect(value.saveProfile.mock.calls[0][0]).toMatchObject({
      fullName: 'Nguyễn Minh Tuấn',
      phone: '0905123456',
      dateOfBirth: '2001-02-03',
      gender: 'FEMALE',
    })
    expect(await screen.findByRole('status')).toBeTruthy()
  })

  it('rejects a future date of birth before calling the profile API', () => {
    const value = auth()
    view(<ProfilePage />, value)
    fill('Ngày sinh', '2999-01-01')
    click('Lưu thay đổi')
    expect(value.saveProfile).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Ngày sinh').getAttribute('aria-invalid')).toBe('true')
  })

  it('allows clearing the gender', async () => {
    const value = auth()
    view(<ProfilePage />, value)
    fill('Giới tính', '')
    click('Lưu thay đổi')
    await waitFor(() => expect(value.saveProfile).toHaveBeenCalledTimes(1))
    expect(value.saveProfile.mock.calls[0][0].gender).toBe('')
  })
})

describe('Address book', () => {
  const fillAddress = (values = {}) => {
    const data = {
      recipient: 'Nguyễn Minh Tuấn',
      phone: '0905123456',
      street: '120 Hoàng Diệu',
      ward: 'Thạch Thang',
      district: 'Hải Châu',
      province: 'Đà Nẵng',
      ...values,
    }
    fill(/Người nhận/, data.recipient)
    fill(/Số điện thoại/, data.phone)
    fill(/Số nhà, tên đường/, data.street)
    fill(/Phường \/ xã/, data.ward)
    fill(/Quận \/ huyện/, data.district)
    fill(/Tỉnh \/ thành/, data.province)
  }
  const newBody = {
    recipientName: 'Nguyễn Minh Tuấn',
    phone: '0905123456',
    streetAddress: '120 Hoàng Diệu',
    ward: 'Thạch Thang',
    district: 'Hải Châu',
    province: 'Đà Nẵng',
    note: null,
  }
  const card = async (name) => screen.findByRole('listitem', { name: `Địa chỉ của ${name}` })

  it('lists addresses with the default marked and only the valid actions', async () => {
    listMyAddresses.mockResolvedValue([home, office])
    view(<AddressBookPage />)
    const first = within(await card('Nguyễn Minh Tuấn'))
    expect(first.getByText('Mặc định')).toBeTruthy()
    expect(first.getByText('120 Hoàng Diệu, Thạch Thang, Hải Châu, Đà Nẵng')).toBeTruthy()
    expect(first.queryByRole('button', { name: 'Thiết lập mặc định' })).toBeNull()
    const second = within(await card('Trần Thu Hà'))
    expect(second.queryByText('Mặc định')).toBeNull()
    expect(second.getByRole('button', { name: 'Thiết lập mặc định' })).toBeTruthy()
    expect(second.getByText('Ghi chú: Gọi trước khi giao')).toBeTruthy()
  })

  it('shows the empty state and creates the first address as the default', async () => {
    listMyAddresses.mockResolvedValueOnce([]).mockResolvedValue([home])
    createAddress.mockResolvedValue(home)
    view(<AddressBookPage />)
    await clickWhenReady('Thêm địa chỉ đầu tiên')
    await screen.findByRole('dialog', { name: 'Thêm địa chỉ nhận hàng' })
    const select = screen.getByLabelText(/Địa chỉ mặc định/)
    expect(select.value).toBe('yes')
    expect(select.disabled).toBe(true)
    fillAddress()
    click('Lưu địa chỉ')
    await waitFor(() => expect(createAddress).toHaveBeenCalledTimes(1))
    expect(createAddress.mock.calls[0][0]).toEqual(newBody)
    expect(typeof createAddress.mock.calls[0][1]).toBe('string')
    expect(setDefaultAddress).not.toHaveBeenCalled()
    expect(await screen.findByText('Đã thêm địa chỉ mới.')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await card('Nguyễn Minh Tuấn')).toBeTruthy()
    expect(listMyAddresses).toHaveBeenCalledTimes(2)
  })

  it('validates required fields and the phone number before calling the API', async () => {
    view(<AddressBookPage />)
    await clickWhenReady('Thêm địa chỉ đầu tiên')
    await screen.findByRole('dialog')
    fillAddress({ recipient: '', phone: '0905 123 456' })
    click('Lưu địa chỉ')
    expect(createAddress).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/Người nhận/).getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByLabelText(/Số điện thoại/).getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByLabelText(/Tỉnh \/ thành/).getAttribute('aria-invalid')).toBeNull()
  })

  it('sets a new address as the default through the dedicated endpoint', async () => {
    listMyAddresses.mockResolvedValue([home])
    createAddress.mockResolvedValue(office)
    setDefaultAddress.mockResolvedValue({ ...office, isDefault: true })
    view(<AddressBookPage />)
    await card('Nguyễn Minh Tuấn')
    click('Thêm địa chỉ mới')
    await screen.findByRole('dialog')
    expect(screen.getByLabelText(/Địa chỉ mặc định/).value).toBe('no')
    fillAddress({ recipient: 'Trần Thu Hà' })
    fill(/Địa chỉ mặc định/, 'yes')
    click('Lưu địa chỉ')
    await waitFor(() => expect(setDefaultAddress).toHaveBeenCalledTimes(1))
    expect(setDefaultAddress.mock.calls[0][0]).toBe('a2')
    expect(createAddress.mock.invocationCallOrder[0]).toBeLessThan(
      setDefaultAddress.mock.invocationCallOrder[0],
    )
  })

  it('keeps the saved address and warns when only the default step fails', async () => {
    listMyAddresses.mockResolvedValue([home])
    createAddress.mockResolvedValue(office)
    setDefaultAddress.mockRejectedValue(apiFailure(503, 'UNAVAILABLE', 'Máy chủ đang bận.'))
    view(<AddressBookPage />)
    await card('Nguyễn Minh Tuấn')
    click('Thêm địa chỉ mới')
    await screen.findByRole('dialog')
    fillAddress()
    fill(/Địa chỉ mặc định/, 'yes')
    click('Lưu địa chỉ')
    expect(await screen.findByText(/chưa đặt được làm mặc định/)).toBeTruthy()
    expect(screen.getByText('Đã thêm địa chỉ mới.')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('edits an address with its values prefilled', async () => {
    listMyAddresses.mockResolvedValue([home, office])
    updateAddress.mockResolvedValue({ ...office, streetAddress: '12 Lê Duẩn' })
    view(<AddressBookPage />)
    fireEvent.click(within(await card('Trần Thu Hà')).getByRole('button', { name: 'Chỉnh sửa' }))
    await screen.findByRole('dialog', { name: 'Chỉnh sửa địa chỉ' })
    expect(screen.getByLabelText(/Người nhận/).value).toBe('Trần Thu Hà')
    expect(screen.getByLabelText(/Ghi chú/).value).toBe('Gọi trước khi giao')
    expect(screen.getByLabelText(/Địa chỉ mặc định/).disabled).toBe(false)
    fill(/Số nhà, tên đường/, '12 Lê Duẩn')
    click('Lưu địa chỉ')
    await waitFor(() => expect(updateAddress).toHaveBeenCalledTimes(1))
    expect(updateAddress.mock.calls[0][0]).toBe('a2')
    expect(updateAddress.mock.calls[0][1]).toMatchObject({
      streetAddress: '12 Lê Duẩn',
      note: 'Gọi trước khi giao',
    })
    expect(await screen.findByText('Đã cập nhật địa chỉ.')).toBeTruthy()
  })

  it('locks the default selector for the current default address', async () => {
    listMyAddresses.mockResolvedValue([home, office])
    view(<AddressBookPage />)
    fireEvent.click(
      within(await card('Nguyễn Minh Tuấn')).getByRole('button', { name: 'Chỉnh sửa' }),
    )
    await screen.findByRole('dialog')
    const select = screen.getByLabelText(/Địa chỉ mặc định/)
    expect(select.value).toBe('yes')
    expect(select.disabled).toBe(true)
  })

  it('retries the same request with one key and uses a new key after an edit', async () => {
    const conflict = apiFailure(409, 'DEFAULT_ADDRESS_CONFLICT', 'Đang đổi địa chỉ mặc định.')
    createAddress.mockRejectedValueOnce(conflict).mockRejectedValueOnce(conflict)
    createAddress.mockResolvedValue(home)
    view(<AddressBookPage />)
    await clickWhenReady('Thêm địa chỉ đầu tiên')
    await screen.findByRole('dialog')
    fillAddress()
    click('Lưu địa chỉ')
    expect(await screen.findByRole('alert')).toBeTruthy()
    click('Lưu địa chỉ')
    await waitFor(() => expect(createAddress).toHaveBeenCalledTimes(2))
    expect(createAddress.mock.calls[1][1]).toBe(createAddress.mock.calls[0][1])
    await screen.findByRole('alert')
    fill(/Số nhà, tên đường/, '54 Nguyễn Lương Bằng')
    click('Lưu địa chỉ')
    await waitFor(() => expect(createAddress).toHaveBeenCalledTimes(3))
    expect(createAddress.mock.calls[2][1]).not.toBe(createAddress.mock.calls[0][1])
  })

  it('maps server field errors onto the form and keeps the dialog open', async () => {
    createAddress.mockRejectedValue({
      response: {
        status: 400,
        data: {
          error: {
            code: 'VALIDATION_ERROR',
            message: 'Dữ liệu không hợp lệ.',
            details: [{ field: 'ward', message: 'Phường/xã không hợp lệ.' }],
          },
        },
      },
    })
    view(<AddressBookPage />)
    await clickWhenReady('Thêm địa chỉ đầu tiên')
    await screen.findByRole('dialog')
    fillAddress()
    click('Lưu địa chỉ')
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('dialog')).toBeTruthy()
  })

  it('closes the dialog with Escape or Quay lại without saving', async () => {
    view(<AddressBookPage />)
    await clickWhenReady('Thêm địa chỉ đầu tiên')
    fireEvent.keyDown(await screen.findByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    await clickWhenReady('Thêm địa chỉ đầu tiên')
    await screen.findByRole('dialog')
    click('Quay lại')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(createAddress).not.toHaveBeenCalled()
  })

  it('sets another address as the default from the list and refreshes it', async () => {
    listMyAddresses.mockResolvedValueOnce([home, office]).mockResolvedValue([
      { ...office, isDefault: true },
      { ...home, isDefault: false },
    ])
    setDefaultAddress.mockResolvedValue({ ...office, isDefault: true })
    view(<AddressBookPage />)
    fireEvent.click(
      within(await card('Trần Thu Hà')).getByRole('button', { name: 'Thiết lập mặc định' }),
    )
    await waitFor(() => expect(setDefaultAddress).toHaveBeenCalledTimes(1))
    expect(setDefaultAddress.mock.calls[0][0]).toBe('a2')
    expect(await screen.findByText('Đã đặt địa chỉ mặc định.')).toBeTruthy()
    await waitFor(() => expect(listMyAddresses).toHaveBeenCalledTimes(2))
    const refreshed = await card('Trần Thu Hà')
    expect(within(refreshed).getByText('Mặc định')).toBeTruthy()
  })

  it('reports a failed default change and allows trying again', async () => {
    listMyAddresses.mockResolvedValue([home, office])
    setDefaultAddress
      .mockRejectedValueOnce(apiFailure(503, 'UNAVAILABLE', 'Chưa đổi được mặc định.'))
      .mockResolvedValueOnce(office)
    view(<AddressBookPage />)
    const button = () =>
      within(screen.getByRole('listitem', { name: 'Địa chỉ của Trần Thu Hà' })).getByRole(
        'button',
        { name: 'Thiết lập mặc định' },
      )
    await card('Trần Thu Hà')
    fireEvent.click(button())
    expect((await screen.findByRole('alert')).textContent).toContain('Chưa đổi được mặc định.')
    fireEvent.click(button())
    await waitFor(() => expect(setDefaultAddress).toHaveBeenCalledTimes(2))
  })

  it('asks for confirmation before deleting and does nothing when cancelled', async () => {
    listMyAddresses.mockResolvedValue([home, office])
    view(<AddressBookPage />)
    fireEvent.click(within(await card('Trần Thu Hà')).getByRole('button', { name: 'Xóa' }))
    await screen.findByRole('dialog', { name: 'Xóa địa chỉ?' })
    expect(screen.queryByText(/sẽ được đặt làm mặc định/)).toBeNull()
    click('Hủy')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(deleteAddress).not.toHaveBeenCalled()
  })

  it('deletes after confirmation and refreshes the list', async () => {
    listMyAddresses.mockResolvedValueOnce([home, office]).mockResolvedValue([home])
    deleteAddress.mockResolvedValue(undefined)
    view(<AddressBookPage />)
    fireEvent.click(within(await card('Trần Thu Hà')).getByRole('button', { name: 'Xóa' }))
    await screen.findByRole('dialog')
    click('Xóa địa chỉ')
    await waitFor(() => expect(deleteAddress).toHaveBeenCalledTimes(1))
    expect(deleteAddress.mock.calls[0][0]).toBe('a2')
    expect(await screen.findByText('Đã xóa địa chỉ.')).toBeTruthy()
    await waitFor(() => expect(screen.queryByRole('listitem', { name: /Trần Thu Hà/ })).toBeNull())
  })

  it('explains that another address becomes the default when deleting the default one', async () => {
    listMyAddresses.mockResolvedValue([home, office])
    view(<AddressBookPage />)
    fireEvent.click(within(await card('Nguyễn Minh Tuấn')).getByRole('button', { name: 'Xóa' }))
    expect(await screen.findByText(/sẽ được đặt làm mặc định/)).toBeTruthy()
  })

  it('keeps the confirmation open on a delete failure and treats a missing address as deleted', async () => {
    listMyAddresses.mockResolvedValue([home, office])
    deleteAddress
      .mockRejectedValueOnce(apiFailure(503, 'UNAVAILABLE', 'Chưa xóa được.'))
      .mockRejectedValueOnce(apiFailure(404, 'ADDRESS_NOT_FOUND', 'Không tìm thấy địa chỉ.'))
    view(<AddressBookPage />)
    fireEvent.click(within(await card('Trần Thu Hà')).getByRole('button', { name: 'Xóa' }))
    await screen.findByRole('dialog')
    click('Xóa địa chỉ')
    expect((await screen.findByRole('alert')).textContent).toContain('Chưa xóa được.')
    expect(screen.getByRole('dialog')).toBeTruthy()
    click('Xóa địa chỉ')
    await waitFor(() => expect(deleteAddress).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Đã xóa địa chỉ.')).toBeTruthy()
    expect(deleteAddress.mock.calls[1][1]).toBe(deleteAddress.mock.calls[0][1])
  })

  it('shows a load failure and recovers on retry', async () => {
    listMyAddresses
      .mockRejectedValueOnce(apiFailure(503, 'UNAVAILABLE', 'Dịch vụ người dùng đang bận.'))
      .mockResolvedValueOnce([home])
    view(<AddressBookPage />)
    expect((await screen.findByRole('alert')).textContent).toContain('Dịch vụ người dùng đang bận.')
    expect(screen.queryByRole('button', { name: 'Thêm địa chỉ mới' })).toBeNull()
    click('Thử lại')
    expect(await card('Nguyễn Minh Tuấn')).toBeTruthy()
  })
})

describe('Change password page', () => {
  const fillPasswords = (current = 'Old@1234', next = 'New@12345', confirm = next) => {
    fill(/^Mật khẩu hiện tại/, current)
    fill(/^Mật khẩu mới/, next)
    fill(/^Xác nhận mật khẩu mới/, confirm)
  }

  it('does not call the API when the confirmation does not match', () => {
    view(<ChangePasswordPage />)
    fillPasswords('Old@1234', 'New@12345', 'New@123456')
    click('Đổi mật khẩu')
    expect(changeMyPassword).not.toHaveBeenCalled()
    expect(screen.getByText('Mật khẩu xác nhận không khớp.')).toBeTruthy()
    expect(screen.getByLabelText(/^Xác nhận mật khẩu mới/).getAttribute('aria-invalid')).toBe(
      'true',
    )
  })

  it('rejects a weak or unchanged new password locally', () => {
    view(<ChangePasswordPage />)
    fillPasswords('Old@1234', 'weakpass')
    click('Đổi mật khẩu')
    fillPasswords('Same@1234', 'Same@1234')
    click('Đổi mật khẩu')
    expect(changeMyPassword).not.toHaveBeenCalled()
    expect(screen.getByText('Mật khẩu mới phải khác mật khẩu hiện tại.')).toBeTruthy()
  })

  it('ends the local session and sends the user to login after success', async () => {
    changeMyPassword.mockResolvedValue({ message: 'ok' })
    const value = auth()
    view(<ChangePasswordPage />, value)
    fillPasswords()
    click('Đổi mật khẩu')
    await waitFor(() =>
      expect(changeMyPassword).toHaveBeenCalledWith({
        currentPassword: 'Old@1234',
        newPassword: 'New@12345',
      }),
    )
    expect(value.invalidateSession).toHaveBeenCalledTimes(1)
    expect(await screen.findByText(/login: Đã đổi mật khẩu/)).toBeTruthy()
  })

  it('shows a wrong current password on its own field and keeps the session', async () => {
    changeMyPassword.mockRejectedValue(
      apiFailure(400, 'INVALID_CURRENT_PASSWORD', 'Mật khẩu hiện tại không đúng.'),
    )
    const value = auth()
    view(<ChangePasswordPage />, value)
    fillPasswords()
    click('Đổi mật khẩu')
    expect(await screen.findByText('Mật khẩu hiện tại không đúng.')).toBeTruthy()
    expect(screen.getByLabelText(/^Mật khẩu hiện tại/).getAttribute('aria-invalid')).toBe('true')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(value.invalidateSession).not.toHaveBeenCalled()
  })

  it('shows a temporary lock as an alert', async () => {
    changeMyPassword.mockRejectedValue(
      apiFailure(423, 'ACCOUNT_LOCKED', 'Bạn đã nhập sai nhiều lần. Vui lòng thử lại sau 15 phút.'),
    )
    view(<ChangePasswordPage />)
    fillPasswords()
    click('Đổi mật khẩu')
    expect((await screen.findByRole('alert')).textContent).toContain('thử lại sau 15 phút')
  })

  it('offers password reset when the account has no password yet', async () => {
    changeMyPassword.mockRejectedValue(
      apiFailure(400, 'PASSWORD_NOT_SET', 'Tài khoản chưa có mật khẩu.'),
    )
    view(<ChangePasswordPage />)
    fillPasswords()
    click('Đổi mật khẩu')
    const link = await screen.findByRole('link', { name: /Đặt mật khẩu/ })
    expect(link.getAttribute('href')).toBe('/forgot-password')
  })

  it('never resends after an unknown outcome and lets the user sign in again', async () => {
    changeMyPassword.mockRejectedValue(
      apiFailure(504, 'PASSWORD_CHANGE_UNKNOWN', 'Chưa xác nhận được kết quả đổi mật khẩu.'),
    )
    const value = auth()
    view(<ChangePasswordPage />, value)
    fillPasswords()
    click('Đổi mật khẩu')
    expect((await screen.findByRole('alert')).textContent).toContain('không gửi lại')
    const submit = screen.getByRole('button', { name: 'Đổi mật khẩu' })
    expect(submit.disabled).toBe(true)
    fireEvent.click(submit)
    fireEvent.submit(submit.closest('form'))
    expect(changeMyPassword).toHaveBeenCalledTimes(1)
    click('Đăng nhập lại')
    await waitFor(() => expect(value.logout).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/login:/)).toBeTruthy()
  })
})
