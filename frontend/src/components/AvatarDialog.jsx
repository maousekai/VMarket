import { useState } from 'react'
import { validateAvatarUrl } from '../auth/avatarValidation'
import { getApiError } from '../services/api/apiClient'
import AccountField from './AccountField'
import Modal from './Modal'

// Changes only the avatar: the other profile fields are sent back as stored.
export default function AvatarDialog({ profile, saveProfile, onClose }) {
  const [value, setValue] = useState(profile.avatarUrl || '')
  const [fieldError, setFieldError] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function submit(input) {
    if (saving) return
    const avatarUrl = input.trim()
    const errors = validateAvatarUrl({ avatarUrl })
    setFieldError(errors.avatarUrl || '')
    setError('')
    if (errors.avatarUrl) return
    setSaving(true)
    try {
      await saveProfile({
        fullName: profile.fullName || '',
        phone: profile.phone || '',
        avatarUrl,
      })
    } catch (failure) {
      const apiError = getApiError(failure)
      setFieldError(apiError.fieldErrors.avatarUrl || '')
      setError(apiError.message)
      setSaving(false)
      return
    }
    onClose()
  }

  return (
    <Modal
      title="Đổi ảnh đại diện"
      description="Dán đường dẫn của ảnh bạn muốn dùng (http hoặc https)."
      onClose={() => !saving && onClose()}
    >
      <form
        className="profile-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          submit(value)
        }}
      >
        <AccountField
          label="Đường dẫn ảnh"
          name="avatarUrl"
          type="url"
          inputMode="url"
          value={value}
          onChange={(event) => {
            setValue(event.target.value)
            setFieldError('')
            setError('')
          }}
          disabled={saving}
          data-autofocus
          maxLength={500}
          placeholder="https://example.com/anh-cua-toi.jpg"
          hint="Nên dùng ảnh vuông. Tối đa 500 ký tự."
          error={fieldError}
        />
        {error && !fieldError && (
          <p className="account-notice account-notice--error" role="alert">
            {error} Bạn có thể thử lại.
          </p>
        )}
        <div className="account-actions">
          <button className="account-button" type="submit" disabled={saving}>
            {saving ? 'Đang lưu…' : 'Lưu ảnh'}
          </button>
          {profile.avatarUrl && (
            <button
              className="account-button account-button--secondary"
              type="button"
              disabled={saving}
              onClick={() => submit('')}
            >
              Xóa ảnh
            </button>
          )}
          <button
            className="account-button account-button--secondary"
            type="button"
            disabled={saving}
            onClick={onClose}
          >
            Hủy
          </button>
        </div>
      </form>
    </Modal>
  )
}
