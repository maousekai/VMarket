// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import { deleteMyMedia, uploadMyAvatar } from '../services/api/mediaApi'
import ChangePasswordPage from './ChangePasswordPage'
import ProfilePage from './ProfilePage'

vi.mock('../services/api/mediaApi', () => ({
  uploadMyAvatar: vi.fn(),
  deleteMyMedia: vi.fn(),
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
  avatarUrl: 'https://cdn.vmarket.vn/avatars/an.jpg',
  dateOfBirth: '1998-08-12',
  gender: 'UNDISCLOSED',
}

function renderPage(page = <ProfilePage />, overrides = {}) {
  const value = {
    session,
    bootstrapState: 'ready',
    userProfile: profile,
    profileState: 'ready',
    loadProfile: vi.fn(),
    saveProfile: vi.fn().mockResolvedValue(profile),
    ...overrides,
  }
  render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>{page}</MemoryRouter>
    </AuthContext.Provider>,
  )
  return value
}

const openDialog = () => fireEvent.click(screen.getByRole('button', { name: 'Đổi ảnh đại diện' }))
const openUrlField = () => {
  fireEvent.click(screen.getByRole('button', { name: 'Chọn ảnh đại diện' }))
  fireEvent.click(screen.getByRole('button', { name: 'Dùng đường dẫn ảnh' }))
}
const field = () => screen.getByLabelText('Đường dẫn ảnh')
const change = (value) => fireEvent.change(field(), { target: { value } })
const saveAvatar = () => {
  if (screen.queryByRole('button', { name: 'Dùng ảnh này' })) {
    fireEvent.click(screen.getByRole('button', { name: 'Dùng ảnh này' }))
  }
  fireEvent.click(screen.getByRole('button', { name: 'Lưu ảnh' }))
}
const imageFile = (name = 'avatar.png') =>
  new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const chooseFile = (file = imageFile()) =>
  fireEvent.change(screen.getByLabelText('Tệp ảnh đại diện'), {
    target: { files: [file] },
  })

beforeEach(() => {
  vi.clearAllMocks()
  deleteMyMedia.mockResolvedValue(undefined)
})

afterEach(cleanup)

describe('Change avatar dialog', () => {
  it('offers the button under the avatar and no longer puts the URL in the profile form', () => {
    renderPage()
    expect(screen.getByRole('button', { name: 'Đổi ảnh đại diện' })).toBeTruthy()
    expect(screen.queryByLabelText(/Đường dẫn ảnh/)).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('is only offered on the profile page and once the profile has loaded', () => {
    renderPage(<ChangePasswordPage />)
    expect(screen.queryByRole('button', { name: 'Đổi ảnh đại diện' })).toBeNull()
    cleanup()
    renderPage(<ProfilePage />, { userProfile: null, profileState: 'loading' })
    expect(screen.queryByRole('button', { name: 'Đổi ảnh đại diện' })).toBeNull()
  })

  it('disables avatar changes while the profile is loading', () => {
    renderPage(<ProfilePage />, { profileState: 'loading' })
    const button = screen.getByRole('button', { name: 'Đổi ảnh đại diện' })
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('opens a dialog with the current avatar URL', async () => {
    renderPage()
    openDialog()
    expect(await screen.findByRole('dialog', { name: 'Đổi ảnh đại diện' })).toBeTruthy()
    openUrlField()
    expect(field().value).toBe(profile.avatarUrl)
  })

  it('saves only the avatar and sends the stored name and phone back', async () => {
    const value = renderPage()
    openDialog()
    await screen.findByRole('dialog')
    openUrlField()
    change('  https://example.com/new.png  ')
    saveAvatar()
    await waitFor(() => expect(value.saveProfile).toHaveBeenCalledTimes(1))
    expect(value.saveProfile.mock.calls[0][0]).toEqual({
      fullName: 'Nguyễn Minh Tuấn',
      phone: '0905123456',
      avatarUrl: 'https://example.com/new.png',
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('removes the picture with Xóa ảnh', async () => {
    const value = renderPage()
    openDialog()
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: 'Xóa ảnh' }))
    await waitFor(() => expect(value.saveProfile).toHaveBeenCalledTimes(1))
    expect(value.saveProfile.mock.calls[0][0].avatarUrl).toBe('')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('hides Xóa ảnh when there is no picture yet', async () => {
    renderPage(<ProfilePage />, { userProfile: { ...profile, avatarUrl: null } })
    openDialog()
    await screen.findByRole('dialog')
    openUrlField()
    expect(field().value).toBe('')
    expect(screen.queryByRole('button', { name: 'Xóa ảnh' })).toBeNull()
  })

  it.each(['ftp://example.com/a.png', 'not a url', 'javascript:alert(1)', 'a'.repeat(501)])(
    'rejects %s before calling the API',
    async (bad) => {
      const value = renderPage()
      openDialog()
      await screen.findByRole('dialog')
      openUrlField()
      change(bad)
      saveAvatar()
      expect(value.saveProfile).not.toHaveBeenCalled()
      openUrlField()
      expect(field().getAttribute('aria-invalid')).toBe('true')
      change('https://example.com/ok.png')
      fireEvent.click(screen.getByRole('button', { name: 'Dùng ảnh này' }))
      expect(screen.queryByRole('alert')).toBeNull()
    },
  )

  it('keeps the dialog open and shows the error when saving fails', async () => {
    const value = renderPage(<ProfilePage />, {
      saveProfile: vi.fn().mockRejectedValue({
        response: {
          status: 503,
          data: { error: { code: 'UNAVAILABLE', message: 'Máy chủ bận.' } },
        },
      }),
    })
    openDialog()
    await screen.findByRole('dialog')
    openUrlField()
    change('https://example.com/new.png')
    saveAvatar()
    expect((await screen.findByRole('alert')).textContent).toContain('Máy chủ bận.')
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(value.saveProfile).toHaveBeenCalledTimes(1)
  })

  it('closes with Hủy or Escape without saving', async () => {
    const value = renderPage()
    openDialog()
    fireEvent.click(await screen.findByRole('button', { name: 'Hủy' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    openDialog()
    fireEvent.keyDown(await screen.findByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(value.saveProfile).not.toHaveBeenCalled()
  })

  it('uploads a selected file only when saving and stores the returned URL', async () => {
    const uploaded = 'http://localhost:9000/vmarket-media/avatars/buyer-one/new.png'
    uploadMyAvatar.mockResolvedValue({ url: uploaded })
    const file = imageFile()
    const value = renderPage()
    openDialog()
    await screen.findByRole('dialog')
    chooseFile(file)
    expect(screen.getByText('Ảnh mới')).toBeTruthy()
    expect(uploadMyAvatar).not.toHaveBeenCalled()

    saveAvatar()
    await waitFor(() => expect(uploadMyAvatar).toHaveBeenCalledWith(file))
    await waitFor(() => expect(value.saveProfile).toHaveBeenCalledTimes(1))
    expect(value.saveProfile.mock.calls[0][0]).toEqual({
      fullName: 'Nguyễn Minh Tuấn',
      phone: '0905123456',
      avatarUrl: uploaded,
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('removes the uploaded file when saving the profile fails', async () => {
    const uploaded = 'http://localhost:9000/vmarket-media/avatars/buyer-one/new.png'
    uploadMyAvatar.mockResolvedValue({ url: uploaded })
    renderPage(<ProfilePage />, {
      saveProfile: vi.fn().mockRejectedValue({
        response: { status: 400, data: { error: { code: 'INVALID', message: 'Không lưu được.' } } },
      }),
    })
    openDialog()
    await screen.findByRole('dialog')
    chooseFile()
    saveAvatar()

    await waitFor(() => expect(deleteMyMedia).toHaveBeenCalledWith(uploaded))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect((await screen.findByRole('alert')).textContent).toContain('Không lưu được.')
  })

  it('does not delete the stored avatar when re-uploading the same image fails', async () => {
    uploadMyAvatar.mockResolvedValue({ url: profile.avatarUrl })
    renderPage(<ProfilePage />, {
      saveProfile: vi.fn().mockRejectedValue({
        response: {
          status: 400,
          data: { error: { code: 'INVALID', message: 'Không lưu được.' } },
        },
      }),
    })
    openDialog()
    await screen.findByRole('dialog')
    chooseFile()
    saveAvatar()

    await screen.findByRole('alert')
    expect(deleteMyMedia).not.toHaveBeenCalled()
  })

  it('rejects a file that is too large before uploading', async () => {
    renderPage()
    openDialog()
    await screen.findByRole('dialog')
    chooseFile(new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'huge.png', { type: 'image/png' }))
    expect(screen.getByRole('alert').textContent).toContain('tối đa 2 MB')
    expect(uploadMyAvatar).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeTruthy()
  })

  it('does not send an avatar when the profile form is saved', async () => {
    const value = renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))
    await waitFor(() => expect(value.saveProfile).toHaveBeenCalledTimes(1))
    expect('avatarUrl' in value.saveProfile.mock.calls[0][0]).toBe(false)
  })
})
