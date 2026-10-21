import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { validateChangePassword } from '../auth/validation'
import AccountField from '../components/AccountField'
import AccountLayout from '../components/AccountLayout'
import { getApiError } from '../services/api/apiClient'
import { changeMyPassword } from '../services/api/userApi'

const loginState = { message: 'Đã đổi mật khẩu. Vui lòng đăng nhập lại bằng mật khẩu mới.' }

export default function ChangePasswordPage() {
  const navigate = useNavigate()
  const { invalidateSession, logout } = useAuth()
  const [values, setValues] = useState({
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  })
  const [fieldErrors, setFieldErrors] = useState({})
  const [failure, setFailure] = useState(null)
  const [saving, setSaving] = useState(false)
  // After 504 PASSWORD_CHANGE_UNKNOWN the request must never be resent.
  const unknown = failure?.code === 'PASSWORD_CHANGE_UNKNOWN'

  function onChange(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
    if (!unknown) setFailure(null)
  }

  async function onSubmit(event) {
    event.preventDefault()
    if (saving || unknown) return
    const errors = validateChangePassword(values)
    setFieldErrors(errors)
    setFailure(null)
    if (Object.keys(errors).length) return
    setSaving(true)
    try {
      await changeMyPassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      })
    } catch (error) {
      const apiError = getApiError(error)
      const serverErrors = { ...apiError.fieldErrors }
      if (apiError.code === 'INVALID_CURRENT_PASSWORD')
        serverErrors.currentPassword = apiError.message || 'Mật khẩu hiện tại không đúng.'
      if (apiError.code === 'PASSWORD_UNCHANGED') serverErrors.newPassword = apiError.message
      setFieldErrors(serverErrors)
      setFailure(apiError)
      setSaving(false)
      return
    }
    // The server revoked every session, including this one.
    invalidateSession()
    navigate('/login', { replace: true, state: loginState })
  }

  async function signInAgain() {
    try {
      await logout()
    } catch {
      invalidateSession()
    }
    navigate('/login', { replace: true })
  }

  const field = (name) => ({
    name,
    value: values[name],
    onChange,
    disabled: saving || unknown,
    error: fieldErrors[name],
    type: 'password',
  })
  const showsOnField = Object.values(fieldErrors).some(Boolean)

  return (
    <AccountLayout title="Đổi mật khẩu" current="/profile/password">
      <section className="account-card profile-card" aria-labelledby="password-heading">
        <h1 id="password-heading">Đổi mật khẩu</h1>
        <p className="account-subtitle">Xác nhận bằng mật khẩu hiện tại.</p>
        <form className="profile-form" noValidate onSubmit={onSubmit}>
          <AccountField
            label="Mật khẩu hiện tại"
            required
            autoComplete="current-password"
            {...field('currentPassword')}
          />
          <AccountField
            label="Mật khẩu mới"
            required
            autoComplete="new-password"
            hint="8–32 ký tự, có chữ hoa, số và ký tự đặc biệt."
            {...field('newPassword')}
          />
          <AccountField
            label="Xác nhận mật khẩu mới"
            required
            autoComplete="new-password"
            {...field('confirmPassword')}
          />
          {unknown && (
            <div className="account-notice account-notice--warning" role="alert">
              <p>
                Chưa rõ mật khẩu đã được đổi hay chưa. Vui lòng không gửi lại yêu cầu: hãy đăng nhập
                lại bằng mật khẩu mới, nếu không được thì dùng mật khẩu cũ.
              </p>
              <p>
                <button className="link-button" type="button" onClick={signInAgain}>
                  Đăng nhập lại
                </button>
              </p>
            </div>
          )}
          {failure && !unknown && !showsOnField && (
            <p className="account-notice account-notice--error" role="alert">
              {failure.message}
              {failure.code === 'PASSWORD_NOT_SET' && (
                <>
                  {' '}
                  <Link to="/forgot-password">Đặt mật khẩu bằng quên mật khẩu</Link>.
                </>
              )}
            </p>
          )}
          <div className="account-actions">
            <button className="account-button" type="submit" disabled={saving || unknown}>
              {saving ? 'Đang đổi…' : 'Đổi mật khẩu'}
            </button>
            <Link className="account-button account-button--secondary" to="/profile">
              Quay lại
            </Link>
          </div>
        </form>
      </section>
    </AccountLayout>
  )
}
