// @vitest-environment jsdom
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import { safeReturnTo } from '../auth/navigation'
import LoginPage from './LoginPage'
import RegisterPage from './RegisterPage'
import VerifyEmailPage from './VerifyEmailPage'
import ForgotPasswordPage from './ForgotPasswordPage'
import ResetPasswordPage from './ResetPasswordPage'
import ProfilePage from './ProfilePage'
import { registerAccount, requestOtp, forgotPassword, resetPassword } from '../services/api/authApi'

vi.mock('../services/api/authApi', () => ({
  registerAccount: vi.fn(),
  requestOtp: vi.fn(),
  forgotPassword: vi.fn(),
  resetPassword: vi.fn(),
}))

const active = {
  userId: 'buyer-one',
  email: 'buyer@example.com',
  roles: ['BUYER'],
  status: 'ACTIVE',
}
const pending = { ...active, status: 'PENDING' }
const draft = { email: active.email, fullName: 'Nguyễn Văn An', phone: '0905123456' }
const failure = (status, code, message) => ({
  response: { status, data: { error: { code, message } } },
})
function deferred() {
  let resolve
  let reject
  const promise = new Promise((success, fail) => {
    resolve = success
    reject = fail
  })
  return { promise, resolve, reject }
}

function Destination() {
  const location = useLocation()
  return (
    <p data-testid="destination">
      {JSON.stringify({ path: location.pathname, state: location.state })}
    </p>
  )
}

function renderPage(page, path, overrides = {}, state = null) {
  let current
  let changeAuth
  const initial = {
    session: null,
    registrationDraft: null,
    bootstrapState: 'ready',
    login: vi.fn(),
    verifyOtp: vi.fn(),
    logout: vi.fn(),
    setRegistrationDraft: vi.fn(),
    clearRegistrationDraft: vi.fn(),
    saveRegistrationProfile: vi.fn(),
    invalidateSession: vi.fn(),
    ...overrides,
    getSnapshot: () => current,
  }
  function Harness() {
    const [value, setValue] = useState(initial)
    current = value
    changeAuth = (changes) => setValue((old) => ({ ...old, ...changes }))
    return (
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={[{ pathname: path, state }]}>
          <Routes>
            <Route path={path} element={page} />
            <Route path="*" element={<Destination />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    )
  }
  const view = render(<Harness />)
  return { ...view, auth: initial, changeAuth: (changes) => changeAuth(changes) }
}

const change = (name, value) => fireEvent.change(screen.getByLabelText(name), { target: { value } })
const destination = () => JSON.parse(screen.getByTestId('destination').textContent)
const fillRegister = () => {
  change('Tên tài khoản', 'buyer.one')
  change('Email', active.email)
  change('Họ và tên', draft.fullName)
  change('Số điện thoại', draft.phone)
  change('Mật khẩu', 'StrongPass1!')
  change('Xác nhận mật khẩu', 'StrongPass1!')
}
const fillReset = () => {
  change('Email', active.email)
  change('Mã đặt lại mật khẩu', '123456')
  change('Mật khẩu mới', 'NewStrong1!')
  change('Xác nhận mật khẩu', 'NewStrong1!')
}

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

describe('Safe post-login navigation', () => {
  it.each([
    'https://evil.test',
    '//evil.test',
    '/\\evil.test',
    '/\n/evil.test',
    '/%5cevil.test',
    '/%2f%2fevil.test',
    '/%0a/evil.test',
    '/login/?next=anything',
    '/verify-email',
    '/profile/../admin',
    '/%61dmin',
  ])('rejects unsafe or unauthorized destination %s', (target) => {
    expect(safeReturnTo(target, ['BUYER'])).toBe('/')
  })
  it('returns a canonical permitted internal destination with query and hash', () => {
    expect(safeReturnTo('/about/../profile?from=cart#phone', ['BUYER'])).toBe(
      '/profile?from=cart#phone',
    )
    expect(safeReturnTo('/profile/../admin', ['ADMIN'])).toBe('/admin')
  })
})

describe('Login flow', () => {
  it('rejects username before calling the login API', async () => {
    const login = vi.fn()
    renderPage(<LoginPage />, '/login', { login })
    change('Email', 'An.Nguyen')
    change('Mật khẩu', 'legacy')
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập', exact: true }))
    expect(await screen.findByText('Email không đúng định dạng.')).toBeTruthy()
    expect(login).not.toHaveBeenCalled()
  })

  it('preserves the entered email for password recovery', async () => {
    renderPage(<LoginPage />, '/login')
    change('Email', active.email)
    fireEvent.click(screen.getByRole('link', { name: 'Quên mật khẩu?' }))
    await screen.findByTestId('destination')
    expect(destination()).toEqual({ path: '/forgot-password', state: { email: active.email } })
  })
  it('preserves returnTo when an existing Pending session redirects to verification', async () => {
    renderPage(<LoginPage />, '/login', { session: pending }, { returnTo: '/profile' })
    await screen.findByTestId('destination')
    expect(destination()).toEqual({ path: '/verify-email', state: { returnTo: '/profile' } })
  })

  it('accepts a legacy non-empty login password and shows the backend suspension error', async () => {
    const login = vi
      .fn()
      .mockRejectedValue(failure(423, 'ACCOUNT_SUSPENDED', 'Tài khoản bị quản trị viên khoá.'))
    renderPage(<LoginPage />, '/login', { login })
    change('Email', active.email)
    change('Mật khẩu', 'legacy')
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập', exact: true }))
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(login).toHaveBeenCalledWith({ email: active.email, password: 'legacy' })
  })
})

describe('Registration flow', () => {
  it('continues registration after OTP delivery fails without registering twice', async () => {
    registerAccount.mockResolvedValue({})
    requestOtp.mockRejectedValue(failure(503, 'EMAIL_SEND_FAILED', 'Chưa gửi được mã.'))
    const flow = renderPage(<RegisterPage />, '/register')
    fillRegister()
    fireEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }))
    await screen.findByTestId('destination')
    expect(destination().path).toBe('/verify-email')
    expect(destination().state.otpError.code).toBe('EMAIL_SEND_FAILED')
    expect(registerAccount).toHaveBeenCalledTimes(1)
    expect(flow.auth.setRegistrationDraft).toHaveBeenCalledWith(draft)
    expect(flow.auth.clearRegistrationDraft).toHaveBeenCalledOnce()
    expect(registerAccount.mock.calls[0][0]).toEqual({
      email: active.email,
      username: 'buyer.one',
      password: 'StrongPass1!',
    })
  })

  it('does not send OTP or mutate the draft after the registration page is left', async () => {
    const request = deferred()
    registerAccount.mockReturnValue(request.promise)
    const flow = renderPage(<RegisterPage />, '/register')
    fillRegister()
    fireEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }))
    fireEvent.click(screen.getAllByRole('link', { name: 'Đăng nhập', exact: true })[0])
    await screen.findByTestId('destination')
    await act(async () => request.resolve({}))
    expect(destination().path).toBe('/login')
    expect(flow.auth.setRegistrationDraft).not.toHaveBeenCalled()
    expect(requestOtp).not.toHaveBeenCalled()
  })
})

describe('Email verification and profile completion', () => {
  it('explains storage failure while still allowing OTP verification', () => {
    renderPage(<VerifyEmailPage />, '/verify-email', {
      registrationDraftWarning: 'Không thể lưu tạm. Bạn có thể nhập lại thông tin tại Hồ sơ.',
    })
    expect(screen.getByText(/Không thể lưu tạm/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Xác thực email', exact: true }).disabled).toBe(false)
  })

  it('takes a verified account with an expired draft to profile with an explanation', async () => {
    const message = 'Thông tin hồ sơ tạm đã hết hạn. Bạn có thể hoàn thiện tại Hồ sơ.'
    renderPage(<VerifyEmailPage />, '/verify-email', {
      session: active,
      registrationDraftWarning: message,
    })
    await screen.findByTestId('destination')
    expect(destination()).toEqual({ path: '/profile', state: { message } })
  })

  it('shows the lost-draft explanation alongside the profile form', () => {
    const message = 'Thông tin hồ sơ tạm đã hết hạn. Bạn có thể hoàn thiện tại Hồ sơ.'
    renderPage(
      <ProfilePage />,
      '/profile',
      { session: active, userProfile: { fullName: 'An', phone: '' }, profileState: 'ready' },
      { message },
    )
    expect(screen.getByText(message)).toBeTruthy()
    expect(screen.getByLabelText('Họ và tên').value).toBe('An')
  })
  it('uses the email from a Pending session restored after the screen mounts', async () => {
    const flow = renderPage(<VerifyEmailPage />, '/verify-email')
    await act(async () => flow.changeAuth({ session: pending }))
    expect(screen.getByLabelText('Email').value).toBe(active.email)
    expect(screen.getByLabelText('Email').readOnly).toBe(true)
    change('Mã xác thực', '123456')
    flow.auth.verifyOtp.mockResolvedValue(active)
    fireEvent.click(screen.getByRole('button', { name: 'Xác thực email', exact: true }))
    await waitFor(() =>
      expect(flow.auth.verifyOtp).toHaveBeenCalledWith({ email: active.email, otp: '123456' }),
    )
  })

  it('retains the draft and retries only the profile save after OTP succeeded', async () => {
    const flow = renderPage(
      <VerifyEmailPage />,
      '/verify-email',
      { registrationDraft: draft },
      { returnTo: '/profile' },
    )
    flow.auth.verifyOtp.mockImplementation(async () => {
      flow.changeAuth({ session: active })
      return active
    })
    flow.auth.saveRegistrationProfile
      .mockRejectedValueOnce(failure(503, 'UNAVAILABLE', 'Chưa lưu được hồ sơ.'))
      .mockResolvedValueOnce({})
    change('Mã xác thực', '123456')
    fireEvent.click(screen.getByRole('button', { name: 'Xác thực email', exact: true }))
    expect(await screen.findByRole('heading', { name: 'Tài khoản đã sẵn sàng' })).toBeTruthy()
    expect(flow.auth.clearRegistrationDraft).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }))
    await screen.findByTestId('destination')
    expect(destination().path).toBe('/profile')
    expect(flow.auth.verifyOtp).toHaveBeenCalledTimes(1)
    expect(flow.auth.saveRegistrationProfile).toHaveBeenCalledTimes(2)
    expect(flow.auth.clearRegistrationDraft).toHaveBeenCalledTimes(1)
  })

  it('allows an active account with a retained registration draft to complete it after returning', () => {
    renderPage(<VerifyEmailPage />, '/verify-email', { session: active, registrationDraft: draft })
    expect(screen.getByRole('heading', { name: 'Tài khoản đã sẵn sàng' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Để sau' })).toBeTruthy()
    expect(screen.queryByLabelText('Mã xác thực')).toBeNull()
  })
})

describe('Forgot/reset password', () => {
  it.each([
    ['a guest', null],
    ['another signed-in account', { ...active, userId: 'other-user', email: 'other@example.com' }],
  ])('does not invalidate %s when password reset succeeds', async (_, session) => {
    resetPassword.mockResolvedValue({})
    const flow = renderPage(<ResetPasswordPage />, '/reset-password', { session })
    fillReset()
    fireEvent.click(screen.getByRole('button', { name: 'Lưu mật khẩu mới' }))
    await screen.findByTestId('destination')
    expect(flow.auth.invalidateSession).not.toHaveBeenCalled()
    expect(flow.auth.login).not.toHaveBeenCalled()
    expect(destination().path).toBe('/login')
  })
  it('continues with the backend generic response for a forgot-password request', async () => {
    forgotPassword.mockResolvedValue({ message: 'Nếu email tồn tại, mã đã được gửi.' })
    renderPage(<ForgotPasswordPage />, '/forgot-password')
    change('Email', 'unknown@example.com')
    fireEvent.click(screen.getByRole('button', { name: 'Gửi mã đặt lại mật khẩu' }))
    await screen.findByTestId('destination')
    expect(destination().state).toEqual({
      email: 'unknown@example.com',
      sent: true,
      message: 'Nếu email tồn tại, mã đã được gửi.',
    })
  })

  it('requires re-login after a successful reset without signing in automatically', async () => {
    resetPassword.mockResolvedValue({})
    const flow = renderPage(<ResetPasswordPage />, '/reset-password', { session: active })
    fillReset()
    fireEvent.click(screen.getByRole('button', { name: 'Lưu mật khẩu mới' }))
    await screen.findByTestId('destination')
    expect(flow.auth.invalidateSession).toHaveBeenCalledTimes(1)
    expect(flow.auth.login).not.toHaveBeenCalled()
    expect(destination().path).toBe('/login')
    expect(destination().state.message).toMatch(/thành công/)
  })

  it('does not invalidate another account that signed in while the reset request was in flight', async () => {
    const request = deferred()
    resetPassword.mockReturnValue(request.promise)
    const flow = renderPage(<ResetPasswordPage />, '/reset-password', { session: active })
    fillReset()
    fireEvent.click(screen.getByRole('button', { name: 'Lưu mật khẩu mới' }))
    await act(async () =>
      flow.changeAuth({ session: { ...active, userId: 'other-user', email: 'other@example.com' } }),
    )
    await act(async () => request.resolve({}))
    expect(flow.auth.invalidateSession).not.toHaveBeenCalled()
    expect(screen.queryByTestId('destination')).toBeNull()
    expect(screen.getByRole('button', { name: 'Lưu mật khẩu mới' }).disabled).toBe(false)
  })

  it('does not navigate after leaving the reset screen during a request', async () => {
    const request = deferred()
    resetPassword.mockReturnValue(request.promise)
    const flow = renderPage(<ResetPasswordPage />, '/reset-password')
    fillReset()
    fireEvent.click(screen.getByRole('button', { name: 'Lưu mật khẩu mới' }))
    fireEvent.click(screen.getByRole('link', { name: 'Quay lại đăng nhập' }))
    await screen.findByTestId('destination')
    await act(async () => request.resolve({}))
    expect(flow.auth.invalidateSession).not.toHaveBeenCalled()
    expect(destination()).toEqual({ path: '/login', state: null })
  })
})
