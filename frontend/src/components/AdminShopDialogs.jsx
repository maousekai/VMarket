import { useEffect, useState } from 'react'
import { getApiError } from '../services/api/apiClient'
import {
  approveShop,
  getAdminShop,
  getAdminShopProfileHistory,
  getAdminShopStatusHistory,
  rejectShop,
  reinstateShop,
  suspendShop,
} from '../services/api/shopApi'
import { formatDate, formatDateTime } from '../utils/formatDate'
import { imageUrlOrEmpty, shopAddress, shopStatusLabels } from '../utils/shop'
import { AccountAvatar } from './AppHeader'
import AccountField from './AccountField'
import Modal from './Modal'
import ShopStatusBadge from './ShopStatusBadge'
import './accountForms.css'
import './adminShops.css'

function ShopLogo({ shop, large = false }) {
  const logo = imageUrlOrEmpty(shop.logoUrl)
  if (logo)
    return (
      <img
        src={logo}
        alt=""
        className={large ? 'admin-shop-logo admin-shop-logo--large' : 'admin-shop-logo'}
      />
    )
  return (
    <span className={large ? 'admin-shop-logo admin-shop-logo--large' : 'admin-shop-logo'}>
      {shop.name?.trim().charAt(0).toLocaleUpperCase('vi') || '?'}
    </span>
  )
}

function ShopCover({ shop }) {
  const cover = imageUrlOrEmpty(shop.coverUrl)
  if (!cover) {
    return (
      <div
        className="admin-shop-cover admin-shop-cover--empty"
        role="img"
        aria-label="Chưa có ảnh bìa"
      >
        <span>Chưa có ảnh bìa</span>
      </div>
    )
  }
  return (
    <div className="admin-shop-cover">
      <img src={cover} alt={`Ảnh bìa ${shop.name || 'gian hàng'}`} />
    </div>
  )
}

function HistorySection({ title, rows, kind }) {
  return (
    <section className="admin-detail" aria-label={title}>
      <h3>{title}</h3>
      {rows.length === 0 ? (
        <p className="account-notice">Chưa có dữ liệu.</p>
      ) : kind === 'status' ? (
        <ol className="admin-shop-timeline">
          {rows.map((row, index) => (
            <li key={`${row.createdAt}-${row.toStatus}-${index}`}>
              <span
                className={`admin-shop-timeline__dot admin-shop-timeline__dot--${row.toStatus?.toLowerCase()}`}
              />
              <div>
                <strong>
                  {row.fromStatus
                    ? `${shopStatusLabels[row.fromStatus] || row.fromStatus} → ${
                        shopStatusLabels[row.toStatus] || row.toStatus
                      }`
                    : `Nộp hồ sơ (${shopStatusLabels[row.toStatus] || row.toStatus})`}
                </strong>
                {row.reason && <p>{row.reason}</p>}
                <small>
                  {row.actorName || 'Người dùng VMarket'} · {formatDateTime(row.createdAt)}
                </small>
              </div>
            </li>
          ))}
        </ol>
      ) : (
        <ul className="admin-shop-changes">
          {rows.map((row, index) => (
            <li key={`${row.createdAt}-${row.fieldName}-${index}`}>
              <strong>{row.fieldName}</strong>
              <span>
                {row.oldValue || '(trống)'} → {row.newValue || '(trống)'}
              </span>
              <small>
                {row.statusAtChange} · {formatDateTime(row.createdAt)}
              </small>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

export function AdminShopDrawer({ shop, onClose, onDecision, onChanged }) {
  const [state, setState] = useState({
    status: 'loading',
    detail: shop,
    history: [],
    changes: [],
    error: '',
  })

  useEffect(() => {
    let current = true
    Promise.all([
      getAdminShop(shop.id),
      getAdminShopStatusHistory(shop.id),
      getAdminShopProfileHistory(shop.id, { page: 0, size: 10 }),
    ])
      .then(([detail, history, changes]) => {
        if (current)
          setState({
            status: 'ready',
            detail,
            history,
            changes: changes.items || [],
            error: '',
          })
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
  }, [shop.id])

  const detail = state.detail
  return (
    <Modal
      title="Hồ sơ gian hàng"
      description="Kiểm tra thông tin trước khi đưa ra quyết định."
      variant="drawer"
      onClose={onClose}
    >
      <div className="admin-detail">
        {state.status === 'error' && (
          <p className="account-notice account-notice--error" role="alert">
            {state.error} Đang hiển thị dữ liệu từ danh sách.
          </p>
        )}
        <ShopCover shop={detail} />
        <div className="admin-shop-summary">
          <ShopLogo shop={detail} large />
          <div>
            <strong>{detail.name}</strong>
            <span>{detail.contactEmail}</span>
            <ShopStatusBadge status={detail.status} />
          </div>
        </div>

        <dl className="admin-detail__list">
          <div>
            <dt>Chủ gian hàng</dt>
            <dd>
              <span className="admin-shop-owner">
                <AccountAvatar
                  name={detail.ownerName || 'Người bán'}
                  avatarUrl={detail.ownerAvatarUrl}
                />
                <span>{detail.ownerName || 'Chưa có tên'}</span>
              </span>
            </dd>
          </div>
          <div>
            <dt>Điện thoại</dt>
            <dd>{detail.contactPhone}</dd>
          </div>
          <div>
            <dt>Địa chỉ lấy hàng</dt>
            <dd>{shopAddress(detail) || '—'}</dd>
          </div>
          <div>
            <dt>Ngày nộp</dt>
            <dd>{formatDateTime(detail.createdAt)}</dd>
          </div>
          {detail.approvedAt && (
            <div>
              <dt>Duyệt lần đầu</dt>
              <dd>{formatDate(detail.approvedAt)}</dd>
            </div>
          )}
          <div>
            <dt>Cập nhật cuối</dt>
            <dd>{formatDateTime(detail.updatedAt)}</dd>
          </div>
        </dl>

        {detail.description && (
          <section>
            <h3>Mô tả</h3>
            <p className="admin-shop-description">{detail.description}</p>
          </section>
        )}
        {detail.policies && (
          <section>
            <h3>Chính sách</h3>
            <p className="admin-shop-description">{detail.policies}</p>
          </section>
        )}
        {detail.statusReason && (
          <p className="account-notice account-notice--warning">
            <strong>Lý do gần nhất</strong>
            <br />
            {detail.statusReason}
          </p>
        )}

        {state.status === 'ready' && (
          <>
            <HistorySection title="Lịch sử xét duyệt" rows={state.history} kind="status" />
            <HistorySection title="Thay đổi hồ sơ gần đây" rows={state.changes} kind="profile" />
          </>
        )}

        <div className="account-actions admin-shop-actions">
          {detail.status === 'PENDING' && (
            <>
              <button
                className="account-button"
                type="button"
                onClick={() => onDecision('approve')}
              >
                Duyệt gian hàng
              </button>
              <button
                className="account-button account-button--danger"
                type="button"
                onClick={() => onDecision('reject')}
              >
                Từ chối
              </button>
            </>
          )}
          {detail.status === 'ACTIVE' && (
            <button
              className="account-button account-button--danger"
              type="button"
              onClick={() => onDecision('suspend')}
            >
              Đình chỉ gian hàng
            </button>
          )}
          {detail.status === 'SUSPENDED' && (
            <button
              className="account-button"
              type="button"
              onClick={() => onDecision('reinstate')}
            >
              Gỡ đình chỉ
            </button>
          )}
          {detail.status === 'REJECTED' && (
            <p className="account-field__hint">
              Người bán cần sửa hồ sơ và gửi lại trước khi Admin có thể duyệt.
            </p>
          )}
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={onClose}
          >
            Đóng
          </button>
        </div>
        {onChanged && state.status === 'ready' && (
          <p className="visually-hidden" aria-live="polite">
            {detail.status === 'PENDING' ? 'Đang chờ quyết định' : shopStatusLabels[detail.status]}
          </p>
        )}
      </div>
    </Modal>
  )
}

const decisionContent = {
  approve: {
    title: 'Duyệt gian hàng?',
    description: 'Gian hàng sẽ hiển thị với người mua và tài khoản chủ shop được cấp quyền SELLER.',
    submit: 'Duyệt gian hàng',
    busy: 'Đang duyệt…',
  },
  reject: {
    title: 'Từ chối hồ sơ gian hàng',
    description: 'Người bán sẽ thấy lý do và có thể sửa hồ sơ để gửi lại.',
    submit: 'Từ chối hồ sơ',
    busy: 'Đang từ chối…',
  },
  suspend: {
    title: 'Đình chỉ gian hàng',
    description: 'Gian hàng và sản phẩm sẽ bị ẩn khỏi người mua cho đến khi được gỡ đình chỉ.',
    submit: 'Đình chỉ gian hàng',
    busy: 'Đang đình chỉ…',
  },
  reinstate: {
    title: 'Gỡ đình chỉ gian hàng?',
    description: 'Gian hàng sẽ hoạt động trở lại và sản phẩm được hiển thị với người mua.',
    submit: 'Gỡ đình chỉ',
    busy: 'Đang gỡ đình chỉ…',
  },
}

export function AdminShopDecisionModal({ mode, shop, onCancel, onDone }) {
  const content = decisionContent[mode]
  const needsReason = mode === 'reject' || mode === 'suspend'
  const [reason, setReason] = useState('')
  const [fieldError, setFieldError] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function submit(event) {
    event.preventDefault()
    if (saving) return
    if (needsReason) {
      const text = reason.trim()
      if (!text) {
        setFieldError('Vui lòng nhập lý do.')
        return
      }
      if (text.length > 500) {
        setFieldError('Lý do tối đa 500 ký tự.')
        return
      }
    }
    setSaving(true)
    setError('')
    try {
      const updated = {
        approve: () => approveShop(shop.id),
        reject: () => rejectShop(shop.id, reason.trim()),
        suspend: () => suspendShop(shop.id, reason.trim()),
        reinstate: () => reinstateShop(shop.id),
      }[mode]()
      onDone(await updated)
    } catch (failure) {
      const apiError = getApiError(failure)
      setFieldError(apiError.fieldErrors.reason || '')
      setError(apiError.message)
      setSaving(false)
    }
  }

  return (
    <Modal
      title={content.title}
      description={content.description}
      onClose={() => !saving && onCancel()}
    >
      <form className="profile-form" noValidate onSubmit={submit}>
        <p className="account-notice">
          <strong>{shop.name}</strong>
          <br />
          Chủ gian hàng: {shop.ownerName || 'Chưa có tên'}
        </p>
        {needsReason && (
          <AccountField
            as="textarea"
            label="Lý do"
            required
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
            error={fieldError}
            hint={`${reason.length}/500 ký tự. Người bán sẽ nhìn thấy lý do này.`}
            placeholder={
              mode === 'reject'
                ? 'Ví dụ: Ảnh logo không rõ nét, thông tin liên hệ chưa hợp lệ.'
                : 'Ví dụ: Vi phạm chính sách nội dung của VMarket.'
            }
          />
        )}
        {error && !fieldError && (
          <p className="account-notice account-notice--error" role="alert">
            {error} Bạn có thể thử lại.
          </p>
        )}
        <div className="account-actions">
          <button
            className={`account-button${needsReason ? ' account-button--danger' : ''}`}
            type="submit"
            disabled={saving}
          >
            {saving ? content.busy : content.submit}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            disabled={saving}
            onClick={onCancel}
            data-autofocus={needsReason ? undefined : true}
          >
            Hủy
          </button>
        </div>
      </form>
    </Modal>
  )
}
