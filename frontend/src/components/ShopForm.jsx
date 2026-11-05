import { useEffect, useMemo, useRef, useState } from 'react'
import { validateShop } from '../auth/validation'
import AccountField from './AccountField'
import ImageUploadField from './ImageUploadField'
import { DA_NANG_PROVINCE, DA_NANG_WARD_GROUPS } from '../data/daNangAddress'
import { getApiError } from '../services/api/apiClient'
import {
  deleteMyShopMedia,
  discardUploaded,
  uploadMyShopCover,
  uploadMyShopLogo,
} from '../services/api/mediaApi'
import './accountForms.css'
import './seller.css'

const emptyValues = {
  name: '',
  description: '',
  logoUrl: '',
  coverUrl: '',
  policies: '',
  contactEmail: '',
  contactPhone: '',
  province: DA_NANG_PROVINCE,
  ward: '',
  streetAddress: '',
}

const shopValuesFrom = (shop) => ({
  name: shop?.name || '',
  description: shop?.description || '',
  logoUrl: shop?.logoUrl || '',
  coverUrl: shop?.coverUrl || '',
  policies: shop?.policies || '',
  contactEmail: shop?.contactEmail || '',
  contactPhone: shop?.contactPhone || '',
  province: shop?.province || DA_NANG_PROVINCE,
  ward: shop?.ward || '',
  streetAddress: shop?.streetAddress || '',
})

// PUT /api/shops/me replaces the whole profile. District is deliberately sent as null:
// Da Nang removed the district level on 01/07/2025 and the old value must be cleared.
function shopRequestBody(values) {
  const optional = (value) => value?.trim() || null
  return {
    name: values.name.trim(),
    description: optional(values.description),
    logoUrl: optional(values.logoUrl),
    coverUrl: optional(values.coverUrl),
    policies: optional(values.policies),
    contactEmail: values.contactEmail.trim(),
    contactPhone: values.contactPhone.trim(),
    province: values.province.trim(),
    district: null,
    ward: values.ward.trim(),
    streetAddress: values.streetAddress.trim(),
  }
}

export default function ShopForm({
  initial,
  onSubmit,
  submitLabel = 'Lưu hồ sơ',
  busyLabel = 'Đang lưu…',
  disabled = false,
  actions,
  notice,
}) {
  const [values, setValues] = useState(() => (initial ? shopValuesFrom(initial) : emptyValues))
  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [uploadStage, setUploadStage] = useState('idle')
  const [logoFile, setLogoFile] = useState(null)
  const [coverFile, setCoverFile] = useState(null)
  const [submitAttempt, setSubmitAttempt] = useState(0)
  const formRef = useRef(null)
  const feedbackRef = useRef(null)
  const hasFieldErrors = Object.values(fieldErrors).some(Boolean)

  // Submit happens at the bottom of a long form. Without this, a validation error
  // leaves the user staring at the button with no visible explanation. Run only
  // after a submit attempt so correcting one field does not yank focus to the next.
  useEffect(() => {
    if (!submitAttempt) return
    const firstInvalid = formRef.current?.querySelector('[aria-invalid="true"]')
    if (firstInvalid) {
      firstInvalid.focus({ preventScroll: true })
      firstInvalid.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
      return
    }
    feedbackRef.current?.focus({ preventScroll: true })
    feedbackRef.current?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  }, [submitAttempt])

  const wards = useMemo(() => {
    const known = DA_NANG_WARD_GROUPS.flatMap((group) => group.wards)
    if (values.ward && !known.includes(values.ward)) return [...known, values.ward]
    return known
  }, [values.ward])

  const provinces = useMemo(() => {
    if (values.province && values.province !== DA_NANG_PROVINCE)
      return [values.province, DA_NANG_PROVINCE]
    return [DA_NANG_PROVINCE]
  }, [values.province])

  function onChange(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
    setError('')
  }

  function onImageChange(name, value) {
    setValues((current) => ({ ...current, [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
    setError('')
  }

  function onImageFile(name, file) {
    if (name === 'logoUrl') setLogoFile(file)
    else setCoverFile(file)
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
    setError('')
  }

  async function handleSubmit(event) {
    event.preventDefault()
    if (saving || disabled) return
    const errors = validateShop(values)
    setFieldErrors(errors)
    setError('')
    if (Object.keys(errors).length) {
      setSubmitAttempt((value) => value + 1)
      return
    }
    setSaving(true)
    setUploadStage('idle')
    const uploadedUrls = []
    try {
      // Files are uploaded only now, when the user has committed to saving. If a
      // later upload or the save itself fails, remove whatever already landed.
      const body = shopRequestBody(values)
      if (logoFile) {
        setUploadStage('uploading')
        const result = await uploadMyShopLogo(logoFile)
        // Content-addressed keys: re-uploading the current image returns the URL
        // that is already stored. That URL must never be removed by cleanup.
        if (result.url !== body.logoUrl) uploadedUrls.push(result.url)
        body.logoUrl = result.url
      }
      if (coverFile) {
        setUploadStage('uploading')
        const result = await uploadMyShopCover(coverFile)
        if (result.url !== body.coverUrl) uploadedUrls.push(result.url)
        body.coverUrl = result.url
      }
      setUploadStage('saving')
      await onSubmit(body)
    } catch (failure) {
      if (uploadedUrls.length) discardUploaded(uploadedUrls, deleteMyShopMedia)
      const apiError = getApiError(failure)
      setFieldErrors(apiError.fieldErrors)
      setError(apiError.message)
      setSubmitAttempt((value) => value + 1)
      setSaving(false)
      setUploadStage('idle')
      return
    }
    setSaving(false)
    setUploadStage('idle')
  }

  const field = (name) => ({
    name,
    value: values[name],
    onChange,
    disabled: disabled || saving,
    error: fieldErrors[name],
  })

  return (
    <form ref={formRef} className="profile-form shop-form" noValidate onSubmit={handleSubmit}>
      {notice}
      {(error || hasFieldErrors) && (
        <div
          ref={feedbackRef}
          className="account-notice account-notice--error"
          role="alert"
          tabIndex={-1}
        >
          <p>{error || 'Vui lòng kiểm tra các trường được đánh dấu bên dưới.'}</p>
          {hasFieldErrors && (
            <ul>
              {Object.entries(fieldErrors).map(([name, message]) =>
                message ? <li key={name}>{message}</li> : null,
              )}
            </ul>
          )}
        </div>
      )}

      <section className="shop-form__section" aria-labelledby="shop-identity-heading">
        <div>
          <h2 id="shop-identity-heading">Thông tin gian hàng</h2>
          <p>Phần này hiển thị với người mua trên trang gian hàng công khai.</p>
        </div>
        <AccountField
          label="Tên gian hàng"
          required
          maxLength={100}
          placeholder="Ví dụ: Tiệm Gốm Hội An"
          hint="3–100 ký tự, không trùng tên gian hàng khác."
          {...field('name')}
        />
        <AccountField
          as="textarea"
          label="Mô tả"
          rows={4}
          maxLength={2000}
          placeholder="Giới thiệu sản phẩm, câu chuyện thương hiệu…"
          hint={`${values.description.length}/2000 ký tự`}
          {...field('description')}
        />
        <div className="shop-form__images">
          <div className="shop-form__image-field">
            <ImageUploadField
              label="Logo"
              value={values.logoUrl}
              file={logoFile}
              onChange={(next) => onImageChange('logoUrl', next)}
              onFileChange={(next) => onImageFile('logoUrl', next)}
              shape="square"
              maxSizeMb={2}
              hint="Ảnh vuông, nền rõ · JPEG, PNG hoặc WebP · tối đa 2 MB."
              error={fieldErrors.logoUrl}
              disabled={disabled || saving}
            />
          </div>
          <div className="shop-form__image-field">
            <ImageUploadField
              label="Ảnh bìa"
              value={values.coverUrl}
              file={coverFile}
              onChange={(next) => onImageChange('coverUrl', next)}
              onFileChange={(next) => onImageFile('coverUrl', next)}
              shape="cover"
              maxSizeMb={5}
              hint="Ảnh ngang 16:9 · JPEG, PNG hoặc WebP · tối đa 5 MB."
              error={fieldErrors.coverUrl}
              disabled={disabled || saving}
            />
          </div>
        </div>
        <AccountField
          as="textarea"
          label="Chính sách đổi trả / vận chuyển"
          rows={4}
          maxLength={5000}
          placeholder="Ví dụ: Đổi trả trong 7 ngày nếu sản phẩm lỗi do vận chuyển."
          hint={`${values.policies.length}/5000 ký tự`}
          {...field('policies')}
        />
      </section>

      <section className="shop-form__section" aria-labelledby="shop-contact-heading">
        <div>
          <h2 id="shop-contact-heading">Liên hệ & địa chỉ lấy hàng</h2>
          <p>Thông tin này chỉ dùng để vận hành, không hiển thị trên trang công khai.</p>
        </div>
        <div className="shop-form__row">
          <AccountField
            label="Email liên hệ"
            required
            type="email"
            inputMode="email"
            maxLength={255}
            placeholder="lienhe@ten-shop.vn"
            {...field('contactEmail')}
          />
          <AccountField
            label="Số điện thoại liên hệ"
            required
            type="tel"
            inputMode="tel"
            maxLength={12}
            placeholder="0912345678"
            {...field('contactPhone')}
          />
        </div>
        <div className="shop-form__row">
          <AccountField
            as="select"
            label="Tỉnh / thành phố"
            required
            disabled
            {...field('province')}
          >
            {provinces.map((province) => (
              <option key={province} value={province}>
                {province}
              </option>
            ))}
          </AccountField>
          <AccountField as="select" label="Phường / xã" required {...field('ward')}>
            <option value="">Chọn phường / xã</option>
            {DA_NANG_WARD_GROUPS.map((group) => (
              <optgroup key={group.label} label={group.label}>
                {group.wards.map((ward) => (
                  <option key={ward} value={ward}>
                    {ward}
                  </option>
                ))}
              </optgroup>
            ))}
            {values.ward && !wards.includes(values.ward) && (
              <option value={values.ward}>{values.ward} (hiện tại)</option>
            )}
          </AccountField>
        </div>
        <AccountField
          label="Số nhà, tên đường"
          required
          maxLength={255}
          placeholder="12 Phạm Phán"
          {...field('streetAddress')}
        />
      </section>

      {!disabled && (
        <div className="account-actions">
          <button className="account-button" type="submit" disabled={saving}>
            {saving ? (uploadStage === 'uploading' ? 'Đang tải ảnh…' : busyLabel) : submitLabel}
          </button>
          {actions}
        </div>
      )}
    </form>
  )
}
