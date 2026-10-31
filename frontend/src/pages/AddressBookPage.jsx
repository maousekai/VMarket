import { useEffect, useRef, useState } from 'react'
import { validateAddress } from '../auth/validation'
import AccountField from '../components/AccountField'
import AccountLayout from '../components/AccountLayout'
import Modal from '../components/Modal'
import { DA_NANG_PROVINCE, DA_NANG_WARD_GROUPS } from '../data/daNangAddress'
import { getApiError } from '../services/api/apiClient'
import {
  addressRequestBody,
  createAddress,
  deleteAddress,
  listMyAddresses,
  newIdempotencyKey,
  setDefaultAddress,
  updateAddress,
} from '../services/api/userApi'

const emptyValues = {
  recipientName: '',
  phone: '',
  streetAddress: '',
  ward: '',
  province: DA_NANG_PROVINCE,
  note: '',
  makeDefault: 'no',
}

const addressLine = (address) =>
  [address.streetAddress, address.ward, address.province].filter(Boolean).join(', ')

const valuesFrom = (address) => ({
  recipientName: address.recipientName || '',
  phone: address.phone || '',
  streetAddress: address.streetAddress || '',
  ward: address.ward || '',
  province: DA_NANG_PROVINCE,
  note: address.note || '',
  makeDefault: address.isDefault ? 'yes' : 'no',
})

function AddressForm({ address, isFirst, onCancel, onSaved }) {
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
    onSaved(address ? 'Đã cập nhật địa chỉ.' : 'Đã thêm địa chỉ mới.', warning)
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

function DeleteDialog({ address, hasOthers, onCancel, onDeleted }) {
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [key] = useState(newIdempotencyKey)

  async function confirm() {
    if (saving) return
    setSaving(true)
    setError('')
    try {
      await deleteAddress(address.id, key)
    } catch (failure) {
      const apiError = getApiError(failure)
      // Already removed elsewhere: the goal of the action is met.
      if (apiError.code !== 'ADDRESS_NOT_FOUND') {
        setError(apiError.message)
        setSaving(false)
        return
      }
    }
    onDeleted()
  }

  return (
    <Modal
      title="Xóa địa chỉ?"
      description={`${address.recipientName} – ${addressLine(address)}`}
      onClose={() => !saving && onCancel()}
    >
      {address.isDefault && hasOthers && (
        <p className="account-notice account-notice--warning">
          Đây là địa chỉ mặc định. Sau khi xóa, một địa chỉ còn lại sẽ được đặt làm mặc định.
        </p>
      )}
      {error && (
        <p className="account-notice account-notice--error" role="alert">
          {error} Bạn có thể thử lại.
        </p>
      )}
      <div className="account-actions">
        <button
          className="account-button account-button--danger"
          type="button"
          disabled={saving}
          onClick={confirm}
        >
          {saving ? 'Đang xóa…' : 'Xóa địa chỉ'}
        </button>
        <button
          className="account-button account-button--secondary"
          type="button"
          disabled={saving}
          data-autofocus
          onClick={onCancel}
        >
          Hủy
        </button>
      </div>
    </Modal>
  )
}

export default function AddressBookPage() {
  const [state, setState] = useState({ status: 'loading', addresses: [], error: '' })
  const [dialog, setDialog] = useState(null)
  const [notices, setNotices] = useState([])
  const [busyId, setBusyId] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let current = true
    listMyAddresses()
      .then((addresses) => {
        if (current) setState({ status: 'ready', addresses, error: '' })
      })
      .catch((failure) => {
        if (current)
          setState((previous) => ({
            ...previous,
            status: 'error',
            error: getApiError(failure).message,
          }))
      })
    return () => {
      current = false
    }
  }, [reloadKey])

  const reload = () => setReloadKey((key) => key + 1)

  function onSaved(message, warning) {
    setDialog(null)
    setNotices([
      { kind: 'success', text: message },
      ...(warning ? [{ kind: 'warning', text: warning }] : []),
    ])
    reload()
  }

  function onDeleted() {
    setDialog(null)
    setNotices([{ kind: 'success', text: 'Đã xóa địa chỉ.' }])
    reload()
  }

  async function makeDefault(address) {
    if (busyId) return
    setBusyId(address.id)
    setNotices([])
    try {
      await setDefaultAddress(address.id, newIdempotencyKey())
      setNotices([{ kind: 'success', text: 'Đã đặt địa chỉ mặc định.' }])
      reload()
    } catch (failure) {
      setNotices([{ kind: 'error', text: `${getApiError(failure).message} Bạn có thể thử lại.` }])
    } finally {
      setBusyId('')
    }
  }

  const { status, addresses } = state
  return (
    <AccountLayout title="Sổ địa chỉ" current="/profile/addresses">
      <section className="address-book" aria-labelledby="address-book-heading">
        <div className="address-book__header">
          <div>
            <h1 id="address-book-heading">Địa chỉ của tôi</h1>
            <p className="account-subtitle">Quản lý địa chỉ nhận hàng của bạn.</p>
          </div>
          {status === 'ready' && (
            <button
              className="account-button"
              type="button"
              onClick={() => setDialog({ type: 'form' })}
            >
              Thêm địa chỉ mới
            </button>
          )}
        </div>
        {notices.map((notice) => (
          <p
            key={notice.text}
            className={`account-notice account-notice--${notice.kind}`}
            role={notice.kind === 'error' ? 'alert' : 'status'}
          >
            {notice.text}
          </p>
        ))}
        {status === 'loading' && (
          <section className="account-card account-state">
            <p role="status">Đang tải sổ địa chỉ…</p>
          </section>
        )}
        {status === 'error' && (
          <section className="account-card account-state">
            <p role="alert">{state.error}</p>
            <button
              className="account-button"
              type="button"
              onClick={() => {
                setState({ status: 'loading', addresses: [], error: '' })
                reload()
              }}
            >
              Thử lại
            </button>
          </section>
        )}
        {status === 'ready' && addresses.length === 0 && (
          <section className="account-card account-state">
            <h2>Chưa có địa chỉ giao hàng</h2>
            <p>Thêm địa chỉ để đặt hàng nhanh hơn.</p>
            <button
              className="account-button"
              type="button"
              onClick={() => setDialog({ type: 'form' })}
            >
              Thêm địa chỉ đầu tiên
            </button>
          </section>
        )}
        {status === 'ready' && addresses.length > 0 && (
          <>
            <ul className="address-list">
              {addresses.map((address) => (
                <li
                  key={address.id}
                  className={`account-card address-card${address.isDefault ? ' address-card--default' : ''}`}
                >
                  <div className="address-card__head">
                    <strong>{address.recipientName}</strong>
                    <span>{address.phone}</span>
                    {address.isDefault && <span className="account-badge">Mặc định</span>}
                  </div>
                  <p className="address-card__line">{addressLine(address)}</p>
                  {address.note && <p className="address-card__note">Ghi chú: {address.note}</p>}
                  <div className="address-card__actions">
                    {!address.isDefault && (
                      <button
                        className="link-button"
                        type="button"
                        disabled={Boolean(busyId)}
                        onClick={() => makeDefault(address)}
                      >
                        Thiết lập mặc định
                      </button>
                    )}
                    <button
                      className="link-button"
                      type="button"
                      onClick={() => setDialog({ type: 'form', address })}
                    >
                      Chỉnh sửa
                    </button>
                    <button
                      className="link-button link-button--danger"
                      type="button"
                      onClick={() => setDialog({ type: 'delete', address })}
                    >
                      Xóa
                    </button>
                  </div>
                </li>
              ))}
            </ul>
            <p className="account-notice">Địa chỉ mặc định được chọn khi đặt hàng.</p>
          </>
        )}
      </section>
      {dialog?.type === 'form' && (
        <AddressForm
          key={dialog.address?.id || 'new'}
          address={dialog.address}
          isFirst={addresses.length === 0}
          onCancel={() => setDialog(null)}
          onSaved={onSaved}
        />
      )}
      {dialog?.type === 'delete' && (
        <DeleteDialog
          address={dialog.address}
          hasOthers={addresses.length > 1}
          onCancel={() => setDialog(null)}
          onDeleted={onDeleted}
        />
      )}
    </AccountLayout>
  )
}
