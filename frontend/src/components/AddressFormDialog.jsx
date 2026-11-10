import { useRef, useState } from 'react'
import { validateAddress } from '../auth/validation'
import AccountField from './AccountField'
import Modal from './Modal'
import { DA_NANG_PROVINCE, DA_NANG_WARD_GROUPS } from '../data/daNangAddress'
import { getApiError } from '../services/api/apiClient'
import {
  addressRequestBody,
  createAddress,
  newIdempotencyKey,
  setDefaultAddress,
  updateAddress,
} from '../services/api/userApi'
import './account.css'
import './accountForms.css'

const emptyValues = {
  recipientName: '',
  phone: '',
  streetAddress: '',
  ward: '',
  province: DA_NANG_PROVINCE,
  note: '',
  makeDefault: 'no',
}

const valuesFrom = (address) => ({
  recipientName: address.recipientName || '',
  phone: address.phone || '',
  streetAddress: address.streetAddress || '',
  ward: address.ward || '',
  province: DA_NANG_PROVINCE,
  note: address.note || '',
  makeDefault: address.isDefault ? 'yes' : 'no',
})

/**
 * Them / sua dia chi trong popup. `onSaved(message, warning, address)` nhan dia chi vua luu de
 * noi goi (so dia chi, trang thanh toan) chon ngay.
 */
export default function AddressFormDialog({ address, isFirst, onCancel, onSaved }) {
  const [values, setValues] = useState(address ? valuesFrom(address) : emptyValues)
  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  // A retry of the same request reuses its key; any edit starts a new request.
  const attempt = useRef(null)
  const forcedDefault = isFirst || Boolean(address?.isDefault)

  function onChange(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
    setError('')
  }

  async function onSubmit(event) {
    event.preventDefault()
    if (saving) return
    const errors = validateAddress(values)
    setFieldErrors(errors)
    setError('')
    if (Object.keys(errors).length) return
    const body = addressRequestBody(values)
    const signature = JSON.stringify({ id: address?.id, body })
    if (attempt.current?.signature !== signature)
      attempt.current = { signature, key: newIdempotencyKey() }
    setSaving(true)
    let saved
    try {
      saved = address
        ? await updateAddress(address.id, body, attempt.current.key)
        : await createAddress(body, attempt.current.key)
    } catch (failure) {
      const apiError = getApiError(failure)
      setFieldErrors(apiError.fieldErrors)
      setError(apiError.message)
      setSaving(false)
      return
    }
    let warning = ''
    if (values.makeDefault === 'yes' && !forcedDefault) {
      try {
        await setDefaultAddress(saved.id, newIdempotencyKey())
      } catch (failure) {
        warning = `Đã lưu địa chỉ nhưng chưa đặt được làm mặc định: ${getApiError(failure).message}`
      }
    }
    onSaved(address ? 'Đã cập nhật địa chỉ.' : 'Đã thêm địa chỉ mới.', warning, saved)
  }

  const field = (name) => ({
    name,
    value: values[name],
    onChange,
    disabled: saving,
    error: fieldErrors[name],
  })

  return (
    <Modal
      title={address ? 'Chỉnh sửa địa chỉ' : 'Thêm địa chỉ nhận hàng'}
      description="Nhập thông tin người nhận và địa chỉ giao."
      onClose={() => !saving && onCancel()}
    >
      <form className="profile-form" noValidate onSubmit={onSubmit}>
        <AccountField
          label="Người nhận"
          required
          maxLength={100}
          data-autofocus
          {...field('recipientName')}
        />
        <AccountField
          label="Số điện thoại"
          required
          type="tel"
          inputMode="tel"
          placeholder="0905123456"
          hint="Số di động Việt Nam, bắt đầu bằng 0 hoặc +84, không có khoảng trắng."
          {...field('phone')}
        />
        <AccountField
          label="Số nhà, tên đường"
          required
          maxLength={255}
          {...field('streetAddress')}
        />
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
        </AccountField>
        <AccountField
          as="select"
          label="Tỉnh / thành"
          required
          hint="Hiện chỉ giao hàng trong Thành phố Đà Nẵng (khu vực Đà Nẵng cũ)."
          name="province"
          value={DA_NANG_PROVINCE}
          disabled
        >
          <option value={DA_NANG_PROVINCE}>{DA_NANG_PROVINCE}</option>
        </AccountField>
        <AccountField label="Ghi chú" maxLength={255} {...field('note')} />
        <AccountField
          as="select"
          label="Địa chỉ mặc định"
          hint={forcedDefault ? 'Địa chỉ này đang là địa chỉ mặc định.' : undefined}
          {...field('makeDefault')}
          value={forcedDefault ? 'yes' : values.makeDefault}
          disabled={saving || forcedDefault}
        >
          <option value="no">Không</option>
          <option value="yes">Có</option>
        </AccountField>
        {error && (
          <p className="account-notice account-notice--error" role="alert">
            {error} Bạn có thể thử lưu lại.
          </p>
        )}
        <div className="account-actions">
          <button className="account-button" type="submit" disabled={saving}>
            {saving ? 'Đang lưu…' : 'Lưu địa chỉ'}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            disabled={saving}
            onClick={onCancel}
          >
            Quay lại
          </button>
        </div>
      </form>
    </Modal>
  )
}
