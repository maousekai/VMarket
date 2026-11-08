import { useEffect, useId, useRef, useState } from 'react'
import { imageUrlOrEmpty } from '../utils/shop'
import Modal from './Modal'
import './imageUploadField.css'

const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const ACCEPTED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp']
const ACCEPT = ACCEPTED_TYPES.join(',')

function validateFile(file, maxSizeMb) {
  const extension = file.name.split('.').pop()?.toLocaleLowerCase('en')
  const typeAllowed =
    ACCEPTED_TYPES.includes(file.type) || (!file.type && ACCEPTED_EXTENSIONS.includes(extension))
  if (!typeAllowed) return 'Chỉ nhận ảnh JPEG, PNG hoặc WebP.'
  if (file.size > maxSizeMb * 1024 * 1024) return `Ảnh tối đa ${maxSizeMb} MB.`
  return ''
}

function UploadIcon() {
  return (
    <svg
      className="image-field__icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5" />
      <path d="M5 15.5V18a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-2.5" />
    </svg>
  )
}

// Image picker that keeps two paths open at once:
//   - choose a file (uploaded later by the form on Save, never on selection)
//   - paste an existing http(s) URL, exactly like the old URL-only fields
// The frame itself is the only trigger: clicking it opens a small chooser so the
// two sources are explicit instead of exposing a second form next to the image.
// The component owns no persistence: `file` and `value` are controlled by the form.
export default function ImageUploadField({
  label,
  value = '',
  file = null,
  onChange,
  onFileChange,
  shape = 'square',
  maxSizeMb = 2,
  error,
  disabled = false,
  required = false,
  showRemove = true,
}) {
  const fileId = useId()
  const urlId = useId()
  const inputRef = useRef(null)
  const urlInputRef = useRef(null)
  const [previewUrl, setPreviewUrl] = useState('')
  const [previewFailed, setPreviewFailed] = useState(false)
  const [localError, setLocalError] = useState('')
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerMode, setPickerMode] = useState('menu')
  const [draftUrl, setDraftUrl] = useState('')

  useEffect(() => {
    if (!file) {
      setPreviewUrl('')
      return undefined
    }
    const objectUrl =
      typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function'
        ? URL.createObjectURL(file)
        : ''
    setPreviewUrl(objectUrl)
    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [file])

  useEffect(() => setPreviewFailed(false), [previewUrl, value])

  // Switching from the source menu to the URL step replaces the focused button,
  // so move focus to the field the user now needs.
  useEffect(() => {
    if (pickerOpen && pickerMode === 'url') urlInputRef.current?.focus()
  }, [pickerOpen, pickerMode])

  const storedUrl = imageUrlOrEmpty(value)
  const shownUrl = previewUrl || storedUrl
  const message = error || localError
  const hasImage = Boolean(shownUrl) && !previewFailed

  function openPicker() {
    if (disabled) return
    setDraftUrl(value)
    setPickerMode('menu')
    setLocalError('')
    setPickerOpen(true)
  }

  function closePicker() {
    setPickerOpen(false)
    setPickerMode('menu')
    setLocalError('')
  }

  function pickFile() {
    closePicker()
    inputRef.current?.click()
  }

  function openUrl() {
    setDraftUrl(value)
    setLocalError('')
    setPickerMode('url')
  }

  function applyUrl() {
    const next = draftUrl.trim()
    if (!next) {
      setLocalError('Vui lòng nhập đường dẫn ảnh.')
      return
    }
    setLocalError('')
    // A pasted URL replaces the pending file.
    onFileChange(null)
    onChange(next)
    closePicker()
  }

  function chooseFile(nextFile) {
    if (disabled) return
    const invalid = validateFile(nextFile, maxSizeMb)
    if (invalid) {
      setLocalError(invalid)
      onFileChange(null)
      return
    }
    setLocalError('')
    onFileChange(nextFile)
  }

  function onFileInput(event) {
    const selected = event.target.files?.[0]
    // Reset immediately so picking the same file again still fires onChange.
    event.target.value = ''
    if (selected) chooseFile(selected)
  }

  function remove() {
    if (disabled) return
    setLocalError('')
    onFileChange(null)
    onChange('')
  }

  return (
    <div
      className={`image-field image-field--${shape}${message ? ' has-error' : ''}${
        disabled ? ' is-disabled' : ''
      }`}
    >
      <div className="image-field__head">
        <span className="image-field__label">
          {label}
          {required && <span aria-hidden="true"> *</span>}
        </span>
        <span className={`image-field__status${file ? ' is-new' : ''}`}>
          {file ? 'Ảnh mới' : storedUrl ? 'Đã đặt' : 'Chưa có ảnh'}
        </span>
      </div>

      <button
        type="button"
        className="image-field__drop"
        aria-label={`Chọn ${label.toLocaleLowerCase('vi')}`}
        disabled={disabled}
        onClick={openPicker}
      >
        {hasImage ? (
          <>
            <img
              src={shownUrl}
              alt=""
              onError={() => setPreviewFailed(true)}
              className={shape === 'square' ? 'is-square' : 'is-cover'}
            />
            <span className="image-field__overlay">Đổi ảnh</span>
          </>
        ) : (
          <span className="image-field__placeholder">
            <UploadIcon />
            <strong>Thêm ảnh</strong>
            <small>JPEG, PNG hoặc WebP</small>
          </span>
        )}
      </button>

      {showRemove && (value || file) && (
        <button
          type="button"
          className="link-button link-button--danger image-field__remove"
          disabled={disabled}
          onClick={remove}
        >
          Xoá ảnh
        </button>
      )}

      <input
        ref={inputRef}
        id={fileId}
        type="file"
        className="image-field__input"
        accept={ACCEPT}
        disabled={disabled}
        aria-label={`Tệp ${label.toLocaleLowerCase('vi')}`}
        onChange={onFileInput}
      />

      {message && !pickerOpen && (
        <p className="account-field__error image-field__error" role="alert">
          {message}
        </p>
      )}

      {pickerOpen && (
        <Modal title={`Chọn ${label.toLocaleLowerCase('vi')}`} onClose={closePicker}>
          {pickerMode === 'menu' ? (
            <div className="image-picker">
              <button
                type="button"
                className="image-picker__option"
                aria-label="Tải ảnh từ máy"
                data-autofocus
                onClick={pickFile}
              >
                <strong>Tải ảnh từ máy</strong>
                <span>Chọn tệp JPEG, PNG hoặc WebP, tối đa {maxSizeMb} MB.</span>
              </button>
              <button
                type="button"
                className="image-picker__option"
                aria-label="Dùng đường dẫn ảnh"
                onClick={openUrl}
              >
                <strong>Dùng đường dẫn ảnh</strong>
                <span>Dán URL http(s) của ảnh đã có sẵn trên internet.</span>
              </button>
            </div>
          ) : (
            <div className="image-picker__url">
              <label htmlFor={urlId}>Đường dẫn ảnh</label>
              <input
                id={urlId}
                ref={urlInputRef}
                type="url"
                inputMode="url"
                value={draftUrl}
                maxLength={500}
                data-autofocus
                placeholder="https://example.com/anh-cua-toi.jpg"
                aria-invalid={error || localError ? 'true' : undefined}
                onChange={(event) => {
                  setLocalError('')
                  setDraftUrl(event.target.value)
                }}
                onKeyDown={(event) => {
                  if (event.key !== 'Enter') return
                  event.preventDefault()
                  applyUrl()
                }}
              />
              {localError && (
                <p className="account-field__error" role="alert">
                  {localError}
                </p>
              )}
              <div className="account-actions">
                <button type="button" className="account-button" onClick={applyUrl}>
                  Dùng ảnh này
                </button>
                <button
                  type="button"
                  className="account-button account-button--secondary"
                  onClick={() => setPickerMode('menu')}
                >
                  Quay lại
                </button>
              </div>
            </div>
          )}
        </Modal>
      )}
    </div>
  )
}
