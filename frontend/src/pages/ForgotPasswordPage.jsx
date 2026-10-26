import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import FormMessage from '../components/FormMessage'
import { validateForgotPassword } from '../auth/validation'
import { forgotPassword } from '../services/api/authApi'
import { getApiError } from '../services/api/apiClient'
import { useAuth } from '../auth/useAuth'
import usePageAction from '../hooks/usePageAction'

export default function ForgotPasswordPage() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [email, setEmail] = useState(location.state?.email || '')
  const [fields, setFields] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const beginAction = usePageAction()
  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const invalid = validateForgotPassword({ email })
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    const action = beginAction(auth)
    if (!action) return
    setBusy(true)
    try {
      const response = await forgotPassword({ email: email.trim().toLowerCase() })
      if (!action.current()) return
      navigate('/reset-password', {
        state: { email: email.trim().toLowerCase(), sent: true, message: response.message },
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
  return (
    <AuthLayout
      title="Quên mật khẩu"
      description="Nhập email của tài khoản VMarket. Chúng tôi sẽ gửi mã đặt lại mật khẩu."
    >
      <FormMessage error={error} />
      <form className="auth-form" onSubmit={submit} noValidate aria-busy={busy}>
        <FormField
          label="Email"
          name="email"
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          disabled={busy}
          autoComplete="email"
          error={fields.email}
          required
        />
        <button className="button button--primary" disabled={busy}>
          {busy ? 'Đang gửi yêu cầu…' : 'Gửi mã đặt lại mật khẩu'}
        </button>
      </form>
      <Link className="button button--secondary" to="/login">
        Quay lại đăng nhập
      </Link>
    </AuthLayout>
  )
}
