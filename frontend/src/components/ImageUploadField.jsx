import { useEffect, useId, useRef, useState } from 'react'
import { imageUrlOrEmpty } from '../utils/shop'
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
// The component owns no persistence: `file` and `value` are controlled by the form.
export default function ImageUploadField({
  label,
  value = '',
  file = null,
  onChange,
  onFileChange,
  shape = 'square',
  maxSizeMb = 2,
  hint,
  error,
  disabled = false,
  required = false,
}) {
  const fileId = useId()
  const urlId = useId()
  const inputRef = useRef(null)
  const [previewUrl, setPreviewUrl] = useState('')
  const [previewFailed, setPreviewFailed] = useState(false)
  const [localError, setLocalError] = useState('')
  const [urlMode, setUrlMode] = useState(false)

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

  // A URL validation error is only visible with the URL field open.
  useEffect(() => {
    if (error && !file) setUrlMode(true)
  }, [error, file])

  const storedUrl = imageUrlOrEmpty(value)
  const shownUrl = previewUrl || storedUrl
  const message = error || localError
  const hasImage = Boolean(shownUrl) && !previewFailed

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
        <div>
          <span className="image-field__label">
            {label}
            {required && <span aria-hidden="true"> *</span>}
          </span>
          {hint && <p className="image-field__hint">{hint}</p>}
        </div>
        <span className={`image-field__status${file ? ' is-new' : ''}`}>
          {file ? 'Ảnh mới' : storedUrl ? 'Đã đặt' : 'Chưa có ảnh'}
        </span>
      </div>

      <div className="image-field__body">
        <button
          type="button"
          className="image-field__drop"
          aria-label={`Chọn ${label.toLocaleLowerCase('vi')}`}
          disabled={disabled}
          onClick={() => inputRef.current?.click()}
        >
          {hasImage ? (
            <>
              <img
                src={shownUrl}
                alt=""
                onError={() => setPreviewFailed(true)}
                className={shape === 'square' ? 'is-square' : 'is-cover'}
              />
              <span className="image-field__overlay">Chọn ảnh khác</span>
            </>
          ) : (
            <span className="image-field__placeholder">
              <UploadIcon />
              <strong>Tải ảnh lên</strong>
              <small>JPEG, PNG hoặc WebP</small>
            </span>
          )}
        </button>

        <div className="image-field__tools">
          <button
            type="button"
            className="account-button account-button--secondary image-field__button"
            disabled={disabled}
            onClick={() => inputRef.current?.click()}
          >
            {hasImage || file ? 'Chọn ảnh khác' : 'Chọn ảnh'}
          </button>
          {(value || file) && (
            <button
              type="button"
              className="link-button link-button--danger"
              disabled={disabled}
              onClick={remove}
            >
              Xoá ảnh
            </button>
          )}
          <button
            type="button"
            className="link-button image-field__url-toggle"
            aria-expanded={urlMode}
            disabled={disabled}
            onClick={() => setUrlMode((open) => !open)}
          >
            {urlMode ? 'Ẩn đường dẫn' : 'Dùng đường dẫn'}
          </button>
          {file && <span className="image-field__filename">{file.name}</span>}
        </div>
      </div>

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

      {urlMode && (
        <div className="image-field__url">
          <label htmlFor={urlId}>Đường dẫn ảnh</label>
          <input
            id={urlId}
            type="url"
            inputMode="url"
            value={value}
            maxLength={500}
            disabled={disabled}
            placeholder="https://example.com/anh-cua-toi.jpg"
            aria-invalid={error ? 'true' : undefined}
            onChange={(event) => {
              setLocalError('')
              // A pasted URL replaces the pending file.
              onFileChange(null)
              onChange(event.target.value)
            }}
          />
          <p>Dán URL http(s) nếu ảnh đã có sẵn trên internet.</p>
        </div>
      )}

      {message && (
        <p className="account-field__error image-field__error" role="alert">
          {message}
        </p>
      )}
    </div>
  )
}
