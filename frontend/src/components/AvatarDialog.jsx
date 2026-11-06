import { useState } from 'react'
import { validateAvatarUrl } from '../auth/avatarValidation'
import { getApiError } from '../services/api/apiClient'
import { deleteMyMedia, uploadMyAvatar } from '../services/api/mediaApi'
import ImageUploadField from './ImageUploadField'
import Modal from './Modal'

// Changes only the avatar: the other profile fields are sent back as stored.
export default function AvatarDialog({ profile, saveProfile, onClose }) {
  const [value, setValue] = useState(profile.avatarUrl || '')
  const [file, setFile] = useState(null)
  const [fieldError, setFieldError] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [stage, setStage] = useState('idle')

  async function submit(nextValue = value, nextFile = file) {
    if (saving) return
    const avatarUrl = nextValue.trim()
    const errors = nextFile ? {} : validateAvatarUrl({ avatarUrl })
    setFieldError(errors.avatarUrl || '')
    setError('')
    if (errors.avatarUrl) return
    setSaving(true)
    let uploadedUrl = ''
    try {
      if (nextFile) {
        setStage('uploading')
        uploadedUrl = (await uploadMyAvatar(nextFile)).url
      }
      setStage('saving')
      await saveProfile({
        fullName: profile.fullName || '',
        phone: profile.phone || '',
        avatarUrl: uploadedUrl || avatarUrl,
      })
    } catch (failure) {
      // The profile was not saved: remove the object we just uploaded so it does
      // not become an orphan. Never delete the URL already stored on the profile:
      // content-addressed keys mean re-uploading the same image returns that URL.
      if (uploadedUrl && uploadedUrl !== (profile.avatarUrl || '')) {
        deleteMyMedia(uploadedUrl).catch(() => {})
      }
      const apiError = getApiError(failure)
      setFieldError(apiError.fieldErrors.avatarUrl || '')
      setError(apiError.message)
      setSaving(false)
      setStage('idle')
      return
    }
    onClose()
  }

  function remove() {
    setValue('')
    setFile(null)
    setFieldError('')
    submit('', null)
  }

  const busyLabel = stage === 'uploading' ? 'Đang tải ảnh…' : 'Đang lưu…'

  return (
    <Modal title="Đổi ảnh đại diện" onClose={() => !saving && onClose()}>
      <form
        className="profile-form avatar-dialog"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <ImageUploadField
          label="Ảnh đại diện"
          value={value}
          file={file}
          onChange={(next) => {
            setValue(next)
            setFieldError('')
            setError('')
          }}
          onFileChange={(next) => {
            setFile(next)
            setFieldError('')
            setError('')
          }}
          shape="square"
          maxSizeMb={2}
          error={fieldError}
          disabled={saving}
        />
        {error && !fieldError && (
          <p className="account-notice account-notice--error" role="alert">
            {error} Bạn có thể thử lại.
          </p>
        )}
        <div className="account-actions">
          <button className="account-button" type="submit" disabled={saving}>
            {saving ? busyLabel : 'Lưu ảnh'}
          </button>
          {(profile.avatarUrl || value || file) && (
            <button
              className="account-button account-button--secondary"
              type="button"
              disabled={saving}
              onClick={remove}
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
