import { useId, useRef, useState } from 'react'
import { getApiError } from '../services/api/apiClient'
import { uploadProductImage } from '../services/api/mediaApi'
import { PRODUCT_LIMITS } from '../services/api/productApi'
import { isVideoUrl } from '../utils/shop'

const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const VIDEO_TYPES = ['video/mp4', 'video/webm', 'video/quicktime']
const MAX_SIZE_MB = 5
const MAX_VIDEO_MB = 30
const MAX_VIDEOS = 1

function validate(file, allowVideo) {
  if (allowVideo && VIDEO_TYPES.includes(file.type)) {
    return file.size > MAX_VIDEO_MB * 1024 * 1024
      ? `${file.name}: video tối đa ${MAX_VIDEO_MB} MB.`
      : ''
  }
  if (!ACCEPTED_TYPES.includes(file.type)) {
    return allowVideo
      ? `${file.name}: chỉ nhận ảnh JPEG, PNG, WebP hoặc video MP4, WebM, MOV.`
      : `${file.name}: chỉ nhận JPEG, PNG hoặc WebP.`
  }
  if (file.size > MAX_SIZE_MB * 1024 * 1024) return `${file.name}: ảnh tối đa ${MAX_SIZE_MB} MB.`
  return ''
}

/**
 * Up to `max` images (9 for products, 5 for return evidence). Files upload as soon as they are picked so the form only keeps
 * URLs; `onUploaded` reports new URLs so the page can clean them up if the product is
 * never saved. The first image is the cover shown on product cards.
 */
export default function ProductImagesField({
  value = [],
  onChange,
  onUploaded,
  error,
  disabled = false,
  upload = uploadProductImage,
  max = PRODUCT_LIMITS.images,
  label = 'Ảnh sản phẩm',
  required = true,
  showCover = true,
  allowVideo = false,
}) {
  const inputId = useId()
  const errorId = useId()
  const inputRef = useRef(null)
  const [uploading, setUploading] = useState(0)
  const [problems, setProblems] = useState([])
  const remaining = max - value.length - uploading
  const unit = allowVideo ? 'tệp' : 'ảnh'

  async function onFiles(fileList) {
    const files = [...fileList]
    if (inputRef.current) inputRef.current.value = ''
    const messages = []
    if (files.length > remaining) {
      messages.push(`Chỉ thêm được ${Math.max(remaining, 0)} ${unit} nữa (tối đa ${max} ${unit}).`)
    }
    let videos = value.filter(isVideoUrl).length
    const accepted = files.slice(0, Math.max(remaining, 0)).filter((file) => {
      let problem = validate(file, allowVideo)
      if (!problem && VIDEO_TYPES.includes(file.type)) {
        if (videos >= MAX_VIDEOS) problem = `${file.name}: chỉ được gửi ${MAX_VIDEOS} video.`
        else videos += 1
      }
      if (problem) messages.push(problem)
      return !problem
    })
    setProblems(messages)
    if (!accepted.length) return
    setUploading((count) => count + accepted.length)
    const results = await Promise.allSettled(accepted.map((file) => upload(file)))
    setUploading((count) => count - accepted.length)
    const urls = results.filter((r) => r.status === 'fulfilled').map((r) => r.value.url)
    const failures = results
      .map((r, index) =>
        r.status === 'rejected' ? `${accepted[index].name}: ${getApiError(r.reason).message}` : '',
      )
      .filter(Boolean)
    if (failures.length) setProblems((current) => [...current, ...failures])
    if (urls.length) {
      onUploaded?.(urls)
      // Cung mot file luon cho cung URL (kho anh dat ten theo noi dung): bo anh trung de thu tu
      // va thao tac doi cho / xoa khong bi lech.
      const known = new Set(value)
      const fresh = [...new Set(urls)].filter((url) => !known.has(url))
      const duplicates = urls.length - fresh.length
      onChange((current) =>
        [...current, ...fresh.filter((url) => !current.includes(url))].slice(0, max),
      )
      if (duplicates) {
        setProblems((current) => [
          ...current,
          `${allowVideo ? 'Tệp' : 'Ảnh'} này đã có trong danh sách.`,
        ])
      }
    }
  }

  const move = (index, offset) =>
    onChange((current) => {
      const next = [...current]
      const [item] = next.splice(index, 1)
      next.splice(index + offset, 0, item)
      return next
    })

  return (
    <div className="product-images" aria-describedby={error ? errorId : undefined}>
      <div className="product-images__head">
        <span id={`${inputId}-label`}>
          {label} {required && <span aria-hidden="true">*</span>}
        </span>
        <span className="product-images__count">
          {value.length}/{max} {unit}
          {allowVideo ? ` · tối đa ${MAX_VIDEOS} video ≤ ${MAX_VIDEO_MB} MB` : ''}
          {showCover ? ' · ảnh đầu tiên là ảnh bìa' : ''}
        </span>
      </div>
      <ul className="product-images__grid" aria-labelledby={`${inputId}-label`}>
        {value.map((url, index) => (
          <li key={url} className="product-images__item">
            {isVideoUrl(url) ? (
              <video src={url} muted preload="metadata" aria-label={`Video ${index + 1}`} />
            ) : (
              <img src={url} alt={`Ảnh ${index + 1}`} />
            )}
            {showCover && index === 0 && <span className="product-images__cover">Ảnh bìa</span>}
            <div className="product-images__actions">
              <button
                type="button"
                aria-label={`Đưa ảnh ${index + 1} lên trước`}
                disabled={disabled || index === 0}
                onClick={() => move(index, -1)}
              >
                ‹
              </button>
              <button
                type="button"
                aria-label={`Xoá ảnh ${index + 1}`}
                disabled={disabled}
                onClick={() => onChange((current) => current.filter((item) => item !== url))}
              >
                ×
              </button>
              <button
                type="button"
                aria-label={`Đưa ảnh ${index + 1} ra sau`}
                disabled={disabled || index === value.length - 1}
                onClick={() => move(index, 1)}
              >
                ›
              </button>
            </div>
          </li>
        ))}
        {Array.from({ length: uploading }, (_, index) => (
          <li key={`uploading-${index}`} className="product-images__item is-uploading">
            <span role="status">Đang tải…</span>
          </li>
        ))}
        {remaining > 0 && (
          <li>
            <label className="product-images__add" htmlFor={inputId}>
              <span aria-hidden="true">+</span>
              {allowVideo ? 'Thêm ảnh/video' : 'Thêm ảnh'}
            </label>
            <input
              ref={inputRef}
              id={inputId}
              className="visually-hidden"
              type="file"
              accept={(allowVideo ? [...ACCEPTED_TYPES, ...VIDEO_TYPES] : ACCEPTED_TYPES).join(',')}
              multiple
              disabled={disabled}
              onChange={(event) => onFiles(event.target.files)}
            />
          </li>
        )}
      </ul>
      {(error || problems.length > 0) && (
        <div id={errorId} className="field-error" role="alert">
          {error && <p>{error}</p>}
          {problems.map((problem) => (
            <p key={problem}>{problem}</p>
          ))}
        </div>
      )}
    </div>
  )
}
