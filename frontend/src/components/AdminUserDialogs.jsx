import { useEffect, useRef, useState } from 'react'
import { validateLockReason } from '../auth/validation'
import { getApiError } from '../services/api/apiClient'
import {
  listUserActivities,
  lockUser,
  newIdempotencyKey,
  unlockUser,
} from '../services/api/userApi'
import { activityText, displayName, hasTemporaryLock, roleLabels } from '../utils/adminUsers'
import { formatDate, formatDateTime } from '../utils/formatDate'
import AccountField from './AccountField'
import { AccountAvatar } from './AppHeader'
import Modal from './Modal'
import './accountForms.css'
import './adminUsers.css'

const ACTIVITY_PAGE_SIZE = 10

function Activities({ userId, refreshKey }) {
  const [activity, setActivity] = useState({
    status: 'loading',
    items: [],
    page: 0,
    totalPages: 0,
    error: '',
  })
  const [loadingMore, setLoadingMore] = useState(false)

  useEffect(() => {
    let current = true
    listUserActivities(userId, { page: 0, size: ACTIVITY_PAGE_SIZE })
      .then((result) => {
        if (current)
          setActivity({
            status: 'ready',
            items: result.items,
            page: result.page,
            totalPages: result.totalPages,
            error: '',
          })
      })
      .catch((failure) => {
        if (current)
          setActivity({
            status: 'error',
            items: [],
            page: 0,
            totalPages: 0,
            error: getApiError(failure).message,
          })
      })
    return () => {
      current = false
    }
  }, [userId, refreshKey])

  async function loadMore() {
    if (loadingMore) return
    setLoadingMore(true)
    try {
      const result = await listUserActivities(userId, {
        page: activity.page + 1,
        size: ACTIVITY_PAGE_SIZE,
      })
      setActivity((previous) => ({
        ...previous,
        items: [...previous.items, ...result.items],
        page: result.page,
        totalPages: result.totalPages,
      }))
    } catch (failure) {
      setActivity((previous) => ({ ...previous, error: getApiError(failure).message }))
    } finally {
      setLoadingMore(false)
    }
  }

  return (
    <section className="admin-detail" aria-label="Lịch sử hoạt động">
      <h3>Lịch sử hoạt động gần đây</h3>
      {activity.status === 'loading' && <p role="status">Đang tải lịch sử…</p>}
      {activity.error && (
        <p className="account-notice account-notice--error" role="alert">
          {activity.error}
        </p>
      )}
      {activity.status === 'ready' && activity.items.length === 0 && (
        <p className="account-notice">Chưa có hoạt động nào.</p>
      )}
      {activity.items.length > 0 && (
        <ul className="admin-activity">
          {activity.items.map((item, index) => (
            <li key={`${item.createdAt}-${item.action}-${index}`}>
              {activityText(item)}
              {item.reason && <small>Lý do: {item.reason}</small>}
              <small>{formatDateTime(item.createdAt)}</small>
            </li>
          ))}
        </ul>
      )}
      {activity.page + 1 < activity.totalPages && (
        <button className="link-button" type="button" disabled={loadingMore} onClick={loadMore}>
          {loadingMore ? 'Đang tải…' : 'Xem thêm'}
        </button>
      )}
    </section>
  )
}

export function StatusBadges({ user }) {
  const locked = user.status === 'LOCKED'
  return (
    <span className="admin-status">
      <span className={`account-badge${locked ? ' account-badge--suspended' : ''}`}>
        {locked ? 'Đã khóa' : 'Đang hoạt động'}
      </span>
      {hasTemporaryLock(user) && (
        <span className="account-badge account-badge--pending">Khóa tạm do nhập sai mật khẩu</span>
      )}
    </span>
  )
}

export function UserDrawer({ user, isSelf, refreshKey, onClose, onLock, onUnlock }) {
  const locked = user.status === 'LOCKED' || hasTemporaryLock(user)
  const name = displayName(user)
  return (
    <Modal title="Hồ sơ người dùng chi tiết" variant="drawer" onClose={onClose}>
      <div className="admin-detail">
        <div className="admin-detail__summary">
          <AccountAvatar name={name} avatarUrl={user.avatarUrl} large />
          <strong>{name}</strong>
          <span className="admin-user__id" title={user.userId}>
            ID: {user.userId}
          </span>
          <span className="admin-roles">
            {user.roles.map((role) => (
              <span key={role} className="role-badge">
                {roleLabels[role] || role}
              </span>
            ))}
          </span>
          <StatusBadges user={user} />
        </div>
        {user.status === 'LOCKED' && (
          <p className="account-notice account-notice--error">
            Tài khoản bị khóa lúc {formatDateTime(user.lockedAt)}.
            {user.lockReason && ` Lý do: ${user.lockReason}`}
          </p>
        )}
        {hasTemporaryLock(user) && (
          <p className="account-notice account-notice--warning">
            Tạm khóa đăng nhập đến {formatDateTime(user.loginLockedUntil)}. Mở khóa cũng gỡ khóa tạm
            này.
          </p>
        )}
        <dl className="admin-detail__list">
          <div>
            <dt>Email</dt>
            <dd>
              {user.email} ({user.emailVerified ? 'đã xác thực' : 'chưa xác thực'})
            </dd>
          </div>
          <div>
            <dt>Số điện thoại</dt>
            <dd>{user.phone || 'Chưa cập nhật'}</dd>
          </div>
          <div>
            <dt>Tên đăng nhập</dt>
            <dd>{user.username}</dd>
          </div>
          <div>
            <dt>Ngày tham gia</dt>
            <dd>{formatDate(user.createdAt)}</dd>
          </div>
        </dl>
        <Activities userId={user.userId} refreshKey={refreshKey} />
        {isSelf && locked === false && (
          <p className="account-field__hint">Bạn không thể khóa chính tài khoản của mình.</p>
        )}
        <div className="account-actions">
          {locked ? (
            <button className="account-button" type="button" onClick={onUnlock}>
              Mở khóa tài khoản
            </button>
          ) : (
            <button
              className="account-button account-button--danger"
              type="button"
              disabled={isSelf}
              onClick={onLock}
            >
              Khóa tài khoản
            </button>
          )}
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={onClose}
          >
            Đóng
          </button>
        </div>
      </div>
    </Modal>
  )
}

export function LockDialog({ user, onCancel, onLocked }) {
  const [reason, setReason] = useState('')
  const [fieldError, setFieldError] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  // A retry with the same reason reuses its key; a different reason is a different request.
  const attempt = useRef(null)

  async function onSubmit(event) {
    event.preventDefault()
    if (saving) return
    const errors = validateLockReason({ reason })
    setFieldError(errors.reason || '')
    setError('')
    if (errors.reason) return
    const trimmed = reason.trim()
    if (attempt.current?.reason !== trimmed)
      attempt.current = { reason: trimmed, key: newIdempotencyKey() }
    setSaving(true)
    let updated
    try {
      updated = await lockUser(user.userId, trimmed, attempt.current.key)
    } catch (failure) {
      const apiError = getApiError(failure)
      setFieldError(apiError.fieldErrors.reason || '')
      setError(apiError.message)
      setSaving(false)
      return
    }
    onLocked(updated)
  }

  return (
    <Modal
      title="Xác nhận khóa tài khoản người dùng"
      description={`Hành động này sẽ chặn đăng nhập và thu hồi phiên làm việc của ${displayName(user)}.`}
      onClose={() => !saving && onCancel()}
    >
      <form className="profile-form" noValidate onSubmit={onSubmit}>
        <AccountField
          as="textarea"
          label="Lý do khóa"
          required
          name="reason"
          rows={4}
          maxLength={500}
          value={reason}
          onChange={(event) => {
            setReason(event.target.value)
            setFieldError('')
            setError('')
          }}
          disabled={saving}
          data-autofocus
          placeholder="Ví dụ: Vi phạm chính sách nền tảng"
          hint="Lý do được lưu vào lịch sử hoạt động để Admin khác xem lại."
          error={fieldError}
        />
        {error && !fieldError && (
          <p className="account-notice account-notice--error" role="alert">
            {error} Bạn có thể thử lại.
          </p>
        )}
        <div className="account-actions">
          <button className="account-button account-button--danger" type="submit" disabled={saving}>
            {saving ? 'Đang khóa…' : 'Khóa tài khoản'}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            disabled={saving}
            onClick={onCancel}
          >
            Hủy
          </button>
        </div>
      </form>
    </Modal>
  )
}

export function UnlockDialog({ user, onCancel, onUnlocked }) {
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [key] = useState(newIdempotencyKey)

  async function confirm() {
    if (saving) return
    setSaving(true)
    setError('')
    let updated
    try {
      updated = await unlockUser(user.userId, key)
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
      return
    }
    onUnlocked(updated)
  }

  return (
    <Modal
      title="Mở khóa tài khoản?"
      description={`${displayName(user)} sẽ có thể đăng nhập lại. Khóa tạm do nhập sai mật khẩu (nếu có) cũng được gỡ.`}
      onClose={() => !saving && onCancel()}
    >
      {error && (
        <p className="account-notice account-notice--error" role="alert">
          {error} Bạn có thể thử lại.
        </p>
      )}
      <div className="account-actions">
        <button className="account-button" type="button" disabled={saving} onClick={confirm}>
          {saving ? 'Đang mở khóa…' : 'Mở khóa'}
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
