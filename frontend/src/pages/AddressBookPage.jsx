import { useEffect, useState } from 'react'
import AccountField from '../components/AccountField'
import AccountLayout from '../components/AccountLayout'
import Modal from '../components/Modal'
import AddressFormDialog from '../components/AddressFormDialog'
import { DA_NANG_PROVINCE, DA_NANG_WARD_GROUPS } from '../data/daNangAddress'
import { getApiError } from '../services/api/apiClient'
import {
  deleteAddress,
  listMyAddresses,
  newIdempotencyKey,
  setDefaultAddress,
} from '../services/api/userApi'

const addressLine = (address) =>
  [address.streetAddress, address.ward, address.province].filter(Boolean).join(', ')

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
        <AddressFormDialog
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
