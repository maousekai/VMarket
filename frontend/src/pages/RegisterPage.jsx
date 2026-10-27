import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import FormMessage from '../components/FormMessage'
import { useAuth } from '../auth/useAuth'
import { normalizePhone } from '../auth/phone'
import { validateRegistration } from '../auth/validation'
import { registerAccount, requestOtp } from '../services/api/authApi'
import { getApiError } from '../services/api/apiClient'
import usePageAction from '../hooks/usePageAction'

export default function RegisterPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const [values, setValues] = useState({
    email: '',
    username: '',
    fullName: '',
    phone: '',
    password: '',
    confirmPassword: '',
  })
  const [fields, setFields] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const beginAction = usePageAction()
  if (auth.session)
    return <Navigate to={auth.session.status === 'PENDING' ? '/verify-email' : '/'} replace />
  const change = (event) => setValues({ ...values, [event.target.name]: event.target.value })
  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const normalized = { ...values, phone: normalizePhone(values.phone) }
    const invalid = validateRegistration(normalized)
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    const action = beginAction(auth)
    if (!action) return
    auth.clearRegistrationDraft()
    setBusy(true)
    try {
      const email = normalized.email.trim().toLowerCase()
      await registerAccount({
        email,
        username: normalized.username.trim(),
        password: normalized.password,
      })
      if (!action.current()) return
      auth.setRegistrationDraft({
        email,
        fullName: normalized.fullName.trim(),
        phone: normalized.phone,
      })
      setValues({ ...normalized, password: '', confirmPassword: '' })
      let otpError = null
      let sent = false
      try {
        await requestOtp({ email })
        if (!action.current()) return
        sent = true
      } catch (failure) {
        if (!action.current()) return
        otpError = getApiError(failure)
      }
      navigate('/verify-email', { replace: true, state: { email, sent, otpError } })
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
      title="Tạo tài khoản mới"
      description="Tham gia VMarket — mua sắm dễ dàng, giao hàng nhanh"
      tabs
    >
      <FormMessage error={error} />
      <form className="auth-form" onSubmit={submit} noValidate aria-busy={busy}>
        <FormField
          label="Họ và tên"
          name="fullName"
          value={values.fullName}
          onChange={change}
          disabled={busy}
          autoComplete="name"
          maxLength={100}
          error={fields.fullName}
        />
        <div className="auth-form__row">
          <FormField
            label="Tên tài khoản"
            name="username"
            value={values.username}
            onChange={change}
            disabled={busy}
            autoComplete="nickname"
            autoCapitalize="none"
            spellCheck={false}
            maxLength={50}
            error={fields.username}
            hint="3–50 ký tự: chữ, số và . _ -"
            required
          />
          <FormField
            label="Số điện thoại"
            name="phone"
            type="tel"
            inputMode="tel"
            value={values.phone}
            onChange={change}
            disabled={busy}
            autoComplete="tel"
            placeholder="0912345678"
            maxLength={15}
            error={fields.phone}
          />
        </div>
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
          placeholder="example@gmail.com"
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
          autoComplete="new-password"
          error={fields.password}
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
          {busy ? 'Đang tạo tài khoản…' : 'Tạo tài khoản'}
        </button>
      </form>
    </AuthLayout>
  )
}
