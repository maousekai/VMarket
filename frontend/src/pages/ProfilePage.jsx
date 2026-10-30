import { useState } from 'react'
import { Link } from 'react-router-dom'
import { normalizePhone } from '../auth/phone'
import { useAuth } from '../auth/useAuth'
import { validateProfile } from '../auth/validation'
import { getApiError } from '../services/api/apiClient'
import AccountField from '../components/AccountField'
import AccountLayout from '../components/AccountLayout'
import '../components/account.css'

const genderOptions = [
  ['', 'Chưa chọn'],
  ['MALE', 'Nam'],
  ['FEMALE', 'Nữ'],
  ['OTHER', 'Khác'],
  ['UNDISCLOSED', 'Không muốn cung cấp'],
]

function ProfileForm({ profile, session, saveProfile }) {
  const [values, setValues] = useState({
    fullName: profile.fullName || '',
    phone: profile.phone || '',
    dateOfBirth: profile.dateOfBirth || '',
    gender: profile.gender || '',
  })
  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)

  function onChange(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
    setError('')
    setSaved(false)
  }

  async function onSubmit(event) {
    event.preventDefault()
    if (saving) return
    const normalized = {
      ...values,
      phone: normalizePhone(values.phone),
    }
    const errors = validateProfile(normalized)
    setFieldErrors(errors)
    setSaved(false)
    setError('')
    if (Object.keys(errors).length) return
    setValues(normalized)
    setSaving(true)
    try {
      await saveProfile(normalized)
      setSaved(true)
    } catch (failure) {
      const apiError = getApiError(failure)
      setFieldErrors(apiError.fieldErrors)
      setError(apiError.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="account-card profile-card" aria-labelledby="profile-heading">
      <h1 id="profile-heading">Hồ sơ cá nhân</h1>
      <p className="account-subtitle">Quản lý thông tin liên hệ của bạn trên VMarket.</p>
      <div className="profile-identity">
        <span>Tên tài khoản</span>
        <strong>{session.username}</strong>
        <span>Email</span>
        <strong>{session.email}</strong>
      </div>
      <form className="profile-form" noValidate onSubmit={onSubmit}>
        <div className="account-field">
          <label htmlFor="profile-fullName">Họ và tên</label>
          <input
            id="profile-fullName"
            name="fullName"
            value={values.fullName}
            onChange={onChange}
            autoComplete="name"
            maxLength={100}
            disabled={saving}
            aria-invalid={Boolean(fieldErrors.fullName)}
            aria-describedby={fieldErrors.fullName ? 'profile-fullName-error' : undefined}
          />
          {fieldErrors.fullName && (
            <p className="account-field__error" id="profile-fullName-error">
              {fieldErrors.fullName}
            </p>
          )}
        </div>
        <AccountField
          label="Số điện thoại"
          name="phone"
          type="tel"
          inputMode="tel"
          value={values.phone}
          onChange={onChange}
          autoComplete="tel"
          maxLength={15}
          disabled={saving}
          placeholder="0905123456"
          hint="Số di động Việt Nam, bắt đầu bằng 0 hoặc +84."
          error={fieldErrors.phone}
        />
        <AccountField
          label="Ngày sinh"
          name="dateOfBirth"
          type="date"
          value={values.dateOfBirth}
          onChange={onChange}
          autoComplete="bday"
          disabled={saving}
          error={fieldErrors.dateOfBirth}
        />
        <AccountField
          as="select"
          label="Giới tính"
          name="gender"
          value={values.gender}
          onChange={onChange}
          disabled={saving}
          error={fieldErrors.gender}
        >
          {genderOptions.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </AccountField>
        {error && (
          <p className="account-notice account-notice--error" role="alert">
            {error} Bạn có thể thử lưu lại.
          </p>
        )}
        {saved && (
          <p className="account-notice account-notice--success" role="status">
            Đã lưu thông tin hồ sơ.
          </p>
        )}
        <div className="account-actions">
          <button className="account-button" type="submit" disabled={saving}>
            {saving ? 'Đang lưu…' : 'Lưu thay đổi'}
          </button>
          <Link className="account-button account-button--secondary" to="/">
            Về trang chủ
          </Link>
        </div>
      </form>
    </section>
  )
}

export default function ProfilePage() {
  const { session, userProfile, profileState, profileError, loadProfile, saveProfile } = useAuth()
  return (
    <AccountLayout title="Hồ sơ cá nhân" current="/profile" canChangeAvatar>
      {profileState === 'error' && !userProfile ? (
        <section className="account-card account-state">
          <h1>Chưa thể tải hồ sơ</h1>
          <p role="alert">{getApiError(profileError).message}</p>
          <button
            className="account-button"
            type="button"
            onClick={() => loadProfile().catch(() => {})}
          >
            Thử lại
          </button>
        </section>
      ) : !userProfile ? (
        <section className="account-card account-state">
          <p role="status">Đang tải hồ sơ…</p>
        </section>
      ) : (
        <ProfileForm
          key={session.userId}
          profile={userProfile}
          session={session}
          saveProfile={saveProfile}
        />
      )}
    </AccountLayout>
  )
}
