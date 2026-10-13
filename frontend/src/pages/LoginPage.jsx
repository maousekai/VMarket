import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import FormMessage from '../components/FormMessage'
import { useAuth } from '../auth/useAuth'
import { validateLogin } from '../auth/validation'
import { getApiError } from '../services/api/apiClient'
import { safeReturnTo } from '../auth/navigation'
import usePageAction from '../hooks/usePageAction'

export default function LoginPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [values, setValues] = useState({ email: '', password: '' })
  const [fields, setFields] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const beginAction = usePageAction()
  if (auth.session && !busy)
    return (
      <Navigate
        to={
          auth.session.status === 'PENDING'
            ? '/verify-email'
            : safeReturnTo(location.state?.returnTo, auth.session.roles)
        }
        replace
        state={{ returnTo: location.state?.returnTo }}
      />
    )
  const change = (event) => setValues({ ...values, [event.target.name]: event.target.value })
  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const invalid = validateLogin(values)
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    const action = beginAction(auth, true)
    if (!action) return
    setBusy(true)
    try {
      const session = await auth.login(values)
      if (!action.current()) return
      setValues({ ...values, password: '' })
      navigate(
        session.status === 'PENDING'
          ? '/verify-email'
          : safeReturnTo(location.state?.returnTo, session.roles),
        { replace: true, state: { returnTo: location.state?.returnTo } },
      )
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
      title="Chào mừng trở lại"
      description="Đăng nhập để khám phá chợ trực tuyến Đà Nẵng"
      tabs
    >
      <FormMessage success>{location.state?.message}</FormMessage>
      <FormMessage error={error} />
      <form className="auth-form" onSubmit={submit} noValidate aria-busy={busy}>
        <FormField
          label="Email"
          name="email"
          type="email"
          value={values.email}
          onChange={change}
          disabled={busy}
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          placeholder="Nhập email"
          error={fields.email}
          maxLength={320}
          required
        />
        <FormField
          label="Mật khẩu"
          name="password"
          type="password"
          value={values.password}
          onChange={change}
          disabled={busy}
          autoComplete="current-password"
          error={fields.password}
          required
        />
        <Link className="auth-forgot" to="/forgot-password" state={{ email: values.email }}>
          Quên mật khẩu?
        </Link>
        <button className="button button--primary" disabled={busy}>
          {busy ? 'Đang đăng nhập…' : 'Đăng nhập'}
        </button>
      </form>
      <p className="auth-footer">
        Chưa có tài khoản? <Link to="/register">Đăng ký ngay</Link>
      </p>
    </AuthLayout>
  )
}
