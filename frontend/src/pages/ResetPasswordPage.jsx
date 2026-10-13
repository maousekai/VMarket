import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import FormMessage from '../components/FormMessage'
import useCountdown from '../hooks/useCountdown'
import { useAuth } from '../auth/useAuth'
import { validateResetPassword, validateForgotPassword } from '../auth/validation'
import { forgotPassword, resetPassword } from '../services/api/authApi'
import { getApiError } from '../services/api/apiClient'
import usePageAction from '../hooks/usePageAction'

export default function ResetPasswordPage() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [values, setValues] = useState({
    email: location.state?.email || '',
    code: '',
    newPassword: '',
    confirmPassword: '',
  })
  const [fields, setFields] = useState({})
  const [error, setError] = useState(null)
  const [message, setMessage] = useState(location.state?.message || '')
  const [busy, setBusy] = useState(false)
  const beginAction = usePageAction()
  const countdown = useCountdown(location.state?.sent ? 60 : 0)
  const change = (event) => setValues({ ...values, [event.target.name]: event.target.value })
  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const invalid = validateResetPassword(values)
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    const action = beginAction(auth)
    if (!action) return
    setBusy(true)
    try {
      await resetPassword({
        email: values.email.trim().toLowerCase(),
        code: values.code,
        newPassword: values.newPassword,
      })
      if (!action.current()) return
      const currentSession = auth.getSnapshot?.().session || auth.session
      if (
        !currentSession ||
        currentSession.email.toLowerCase() === values.email.trim().toLowerCase()
      )
        auth.invalidateSession()
      navigate('/login', {
        replace: true,
        state: { message: 'Đặt lại mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới.' },
      })
    } catch (failure) {
      if (!action.current()) return
      const apiError = getApiError(failure)
      setError(apiError)
      setFields(apiError.fieldErrors || {})
    } finally {
      if (action.mounted()) setBusy(false)
      action.finish()
    }
  }
  async function resend() {
    if (busy || countdown.remaining) return
    const invalid = validateForgotPassword(values)
    setFields({ email: invalid.email })
    if (invalid.email) return
    const action = beginAction(auth)
    if (!action) return
    setBusy(true)
    setError(null)
    try {
      const response = await forgotPassword({ email: values.email.trim().toLowerCase() })
      if (!action.current()) return
      setMessage(response.message)
      countdown.start()
    } catch (failure) {
      if (!action.current()) return
      setError(getApiError(failure))
    } finally {
      if (action.mounted()) setBusy(false)
      action.finish()
    }
  }
  return (
    <AuthLayout
      title="Đặt mật khẩu mới"
      description="Nhập mã 6 số được gửi qua email và mật khẩu mới của bạn."
    >
      <FormMessage>{message}</FormMessage>
      <FormMessage error={error} />
      <form className="auth-form" onSubmit={submit} noValidate aria-busy={busy}>
        <FormField
          label="Email"
          name="email"
          type="email"
          value={values.email}
          onChange={change}
          disabled={busy}
          autoComplete="email"
          error={fields.email}
          required
        />
        <FormField
          label="Mã đặt lại mật khẩu"
          name="code"
          value={values.code}
          onChange={change}
          disabled={busy}
          autoComplete="one-time-code"
          inputMode="numeric"
          maxLength={6}
          error={fields.code}
          required
        />
        <FormField
          label="Mật khẩu mới"
          name="newPassword"
          type="password"
          value={values.newPassword}
          onChange={change}
          disabled={busy}
          autoComplete="new-password"
          error={fields.newPassword}
          hint="8–32 ký tự, có chữ hoa, số và ký tự đặc biệt"
          required
        />
        <FormField
          label="Xác nhận mật khẩu"
          name="confirmPassword"
          type="password"
          value={values.confirmPassword}
          onChange={change}
          disabled={busy}
          autoComplete="new-password"
          error={fields.confirmPassword}
          required
        />
        <button className="button button--primary" disabled={busy}>
          {busy ? 'Đang lưu…' : 'Lưu mật khẩu mới'}
        </button>
      </form>
      <button
        className="button button--secondary"
        disabled={busy || countdown.remaining > 0}
        onClick={resend}
      >
        {countdown.remaining ? `Gửi lại mã sau ${countdown.remaining}s` : 'Gửi lại mã'}
      </button>
      <Link className="auth-footer" to="/login">
        Quay lại đăng nhập
      </Link>
    </AuthLayout>
  )
}
