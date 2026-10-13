import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import AuthLayout from '../components/AuthLayout'
import FormField from '../components/FormField'
import FormMessage from '../components/FormMessage'
import useCountdown from '../hooks/useCountdown'
import { useAuth } from '../auth/useAuth'
import { validateOtp } from '../auth/validation'
import { requestOtp } from '../services/api/authApi'
import { getApiError } from '../services/api/apiClient'
import { safeReturnTo } from '../auth/navigation'
import usePageAction from '../hooks/usePageAction'

export default function VerifyEmailPage() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [values, setValues] = useState({
    email: auth.session?.email || auth.registrationDraft?.email || location.state?.email || '',
    otp: '',
  })
  const [error, setError] = useState(location.state?.otpError || null)
  const [fields, setFields] = useState({})
  const [message, setMessage] = useState(
    location.state?.sent ? 'Mã xác thực đã được gửi. Vui lòng kiểm tra email của bạn.' : '',
  )
  const [phase, setPhase] = useState('verify')
  const [busy, setBusy] = useState(false)
  const beginAction = usePageAction()
  const email = auth.session?.email || auth.registrationDraft?.email || values.email
  const countdown = useCountdown(
    location.state?.sent || location.state?.otpError?.code === 'EMAIL_SEND_FAILED' ? 60 : 0,
  )
  if (
    auth.session?.status === 'ACTIVE' &&
    ['verify', 'profile-error'].includes(phase) &&
    !auth.registrationDraft
  )
    return <Navigate to={safeReturnTo(location.state?.returnTo, auth.session.roles)} replace />
  const change = (event) => setValues({ ...values, [event.target.name]: event.target.value })
  function finish() {
    auth.clearRegistrationDraft()
    navigate(safeReturnTo(location.state?.returnTo, auth.session?.roles), { replace: true })
  }
  async function persistProfile() {
    const action = beginAction(auth)
    if (!action) return
    setBusy(true)
    setError(null)
    try {
      await auth.saveRegistrationProfile()
      if (!action.current()) return
      finish()
    } catch (failure) {
      if (!action.current()) return
      setPhase('profile-error')
      setError(getApiError(failure))
    } finally {
      if (action.mounted()) setBusy(false)
      action.finish()
    }
  }
  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const invalid = validateOtp({ ...values, email })
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    const action = beginAction(auth, true)
    if (!action) return
    setBusy(true)
    setPhase('working')
    try {
      const session = await auth.verifyOtp({ ...values, email })
      if (!action.current()) return
      setValues({ ...values, otp: '' })
      if (auth.registrationDraft) {
        try {
          await auth.saveRegistrationProfile()
          if (!action.current()) return
        } catch (failure) {
          if (!action.current()) return
          setPhase('profile-error')
          setError(getApiError(failure))
          return
        }
      }
      navigate(safeReturnTo(location.state?.returnTo, session.roles), { replace: true })
    } catch (failure) {
      if (!action.current()) return
      setPhase('verify')
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
    const invalid = validateOtp({ ...values, email, otp: '000000' })
    setFields({ email: invalid.email })
    if (invalid.email) return
    const action = beginAction(auth)
    if (!action) return
    setBusy(true)
    setError(null)
    setMessage('')
    try {
      await requestOtp({ email: email.trim().toLowerCase() })
      if (!action.current()) return
      countdown.start()
      setMessage('Mã xác thực đã được gửi. Mã có hiệu lực trong 5 phút.')
    } catch (failure) {
      if (!action.current()) return
      const apiError = getApiError(failure)
      setError(apiError)
      if (['EMAIL_SEND_FAILED', 'OTP_RESEND_TOO_SOON'].includes(apiError.code)) countdown.start()
    } finally {
      if (action.mounted()) setBusy(false)
      action.finish()
    }
  }
  if (
    phase === 'profile-error' ||
    (auth.session?.status === 'ACTIVE' && auth.registrationDraft && phase === 'verify')
  )
    return (
      <AuthLayout
        title="Tài khoản đã sẵn sàng"
        description="Bạn đã xác thực email và đăng nhập thành công."
      >
        <FormMessage>
          Tài khoản đã tạo, hồ sơ chưa lưu được. Bạn có thể thử lại hoặc hoàn thiện tại Hồ sơ người
          dùng sau.
        </FormMessage>
        <FormMessage error={error} />
        <div className="auth-actions">
          <button className="button button--primary" disabled={busy} onClick={persistProfile}>
            {busy ? 'Đang lưu…' : 'Thử lại'}
          </button>
          <button className="button button--secondary" disabled={busy} onClick={finish}>
            Để sau
          </button>
        </div>
      </AuthLayout>
    )
  return (
    <AuthLayout title="Xác thực email" description="Nhập mã 6 số để hoàn tất tài khoản VMarket.">
      <FormMessage>{message}</FormMessage>
      <FormMessage error={error} />
      <form className="auth-form" onSubmit={submit} noValidate aria-busy={busy}>
        <FormField
          label="Email"
          name="email"
          type="email"
          value={email}
          onChange={change}
          disabled={busy}
          autoComplete="email"
          readOnly={Boolean(auth.session?.email || auth.registrationDraft?.email)}
          error={fields.email}
          required
        />
        <FormField
          label="Mã xác thực"
          name="otp"
          value={values.otp}
          onChange={change}
          disabled={busy}
          autoComplete="one-time-code"
          inputMode="numeric"
          maxLength={6}
          error={fields.otp}
          placeholder="Nhập mã 6 số"
          required
        />
        <button className="button button--primary" disabled={busy}>
          {busy ? 'Đang xác thực…' : 'Xác thực email'}
        </button>
      </form>
      <button
        className="button button--secondary"
        disabled={busy || countdown.remaining > 0}
        onClick={resend}
      >
        {countdown.remaining ? `Gửi lại mã sau ${countdown.remaining}s` : 'Gửi mã xác thực'}
      </button>
      <p className="auth-footer">
        {auth.session ? (
          <button
            className="text-button"
            disabled={busy}
            onClick={async () => {
              const action = beginAction(auth, true)
              if (!action) return
              setBusy(true)
              setError(null)
              try {
                await auth.logout()
                if (!action.current()) return
                navigate('/login')
              } catch (failure) {
                if (!action.current()) return
                setError(getApiError(failure))
              } finally {
                if (action.mounted()) setBusy(false)
                action.finish()
              }
            }}
          >
            Đăng xuất
          </button>
        ) : (
          <Link to="/login">Quay lại đăng nhập</Link>
        )}
      </p>
    </AuthLayout>
  )
}
