import { useEffect, useState } from 'react'
import Modal from '../components/Modal'
import { getApiError } from '../services/api/apiClient'
import { confirmRefund, listPendingRefunds, listReconciliations } from '../services/api/paymentApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { shortOrderId } from '../utils/order'
import { paymentCode, PAYMENT_STATUS_LABELS } from '../utils/payment'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/adminCatalog.css'
import '../components/payment.css'

const PAGE_SIZE = 20
const TABS = [
  ['refunds', 'Hoàn tiền chờ chuyển'],
  ['reconciliations', 'Cần đối soát'],
]

function refundSource(refund) {
  if (refund.returnId?.startsWith('CANCELLATION_')) return 'Huỷ đơn đã thanh toán'
  if (refund.returnId) return 'Trả hàng được chấp nhận'
  return 'Hoàn tiền thủ công'
}

function ConfirmRefundDialog({ refund, onClose, onDone }) {
  const [reference, setReference] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function submit(event) {
    event.preventDefault()
    if (!reference.trim()) return setError('Nhập mã giao dịch chuyển khoản hoàn tiền.')
    setSaving(true)
    setError('')
    try {
      onDone(await confirmRefund(refund.id, reference.trim()))
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }

  return (
    <Modal
      title="Xác nhận đã hoàn tiền"
      description={`Giao dịch GD${refund.paymentId} · hoàn ${formatMoney(refund.amount)}`}
      onClose={onClose}
    >
      <form className="admin-catalog__form" noValidate onSubmit={submit}>
        <label>
          <span>Mã giao dịch ngân hàng *</span>
          <input
            data-autofocus
            maxLength={128}
            value={reference}
            onChange={(event) => setReference(event.target.value)}
          />
        </label>
        <p className="order-detail__hint">
          Chỉ xác nhận sau khi đã chuyển khoản cho người mua. Mã này hiển thị trong lịch sử giao
          dịch của người mua.
        </p>
        {error && (
          <p className="form-message form-message--error" role="alert">
            {error}
          </p>
        )}
        <div className="account-actions">
          <button
            type="button"
            className="account-button account-button--secondary"
            onClick={onClose}
          >
            Huỷ
          </button>
          <button type="submit" className="account-button" disabled={saving}>
            {saving ? 'Đang lưu…' : 'Xác nhận đã hoàn'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

/** Admin: hoan tien thu cong (chuyen khoan) va cac giao dich VNPay can doi soat. */
export default function AdminPaymentsPage() {
  const [tab, setTab] = useState('refunds')
  const [page, setPage] = useState(0)
  const [state, setState] = useState({ status: 'loading', items: [], error: '' })
  const [reload, setReload] = useState(0)
  const [selected, setSelected] = useState(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let current = true
    setState((value) => ({ ...value, status: 'loading', error: '' }))
    const load = tab === 'refunds' ? listPendingRefunds : listReconciliations
    load({ page, size: PAGE_SIZE })
      .then((items) => current && setState({ status: 'ready', items, error: '' }))
      .catch(
        (failure) =>
          current && setState({ status: 'error', items: [], error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [tab, page, reload])

  return (
    <section className="admin-catalog" aria-labelledby="admin-payments-heading">
      <div className="admin-users__header">
        <div>
          <span className="account-eyebrow">Tài chính</span>
          <h1 id="admin-payments-heading">Thanh toán & hoàn tiền</h1>
          <p className="account-subtitle">
            Ghi nhận các khoản hoàn tiền đã chuyển cho người mua và theo dõi giao dịch cần đối soát.
          </p>
        </div>
      </div>
      <div className="admin-users__toolbar">
        <div className="admin-users__tabs" role="group" aria-label="Loại giao dịch">
          {TABS.map(([value, label]) => (
            <button
              key={value}
              type="button"
              className="admin-users__tab"
              aria-pressed={tab === value}
              onClick={() => {
                setTab(value)
                setPage(0)
                setNotice('')
              }}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
        </p>
      )}
      {state.status === 'loading' && !state.items.length && (
        <p className="account-notice" role="status">
          Đang tải…
        </p>
      )}
      {state.status === 'error' && (
        <div className="account-card account-state">
          <p role="alert">{state.error}</p>
          <button
            className="account-button"
            type="button"
            onClick={() => setReload((value) => value + 1)}
          >
            Thử lại
          </button>
        </div>
      )}
      {state.status === 'ready' && !state.items.length && (
        <p className="admin-users__empty">
          {tab === 'refunds'
            ? 'Không có khoản hoàn tiền nào đang chờ.'
            : 'Không có giao dịch nào cần đối soát.'}
        </p>
      )}
      {state.items.length > 0 && (
        <section className="account-card">
          <ul className="payment-list">
            {tab === 'refunds'
              ? state.items.map((refund) => (
                  <li key={refund.id} className="payment-row">
                    <span>
                      <strong>
                        GD{refund.paymentId} · {refundSource(refund)}
                      </strong>
                      <small>
                        Yêu cầu lúc {formatDateTime(refund.requestedAt)}
                        {refund.reason && ` · ${refund.reason}`}
                      </small>
                    </span>
                    <span className="payment-row__amount">{formatMoney(refund.amount)}</span>
                    <button
                      type="button"
                      className="account-button"
                      onClick={() => setSelected(refund)}
                    >
                      Xác nhận đã hoàn
                    </button>
                  </li>
                ))
              : state.items.map((payment) => (
                  <li key={payment.id} className="payment-row">
                    <span>
                      <strong>
                        {paymentCode(payment)} · Đơn {shortOrderId(payment.orderId)}
                      </strong>
                      <small>
                        {PAYMENT_STATUS_LABELS[payment.status] || payment.status}
                        {payment.expiresAt && ` · hạn ${formatDateTime(payment.expiresAt)}`}
                      </small>
                    </span>
                    <span className="payment-status payment-status--warning">Cần đối soát</span>
                    <span className="payment-row__amount">{formatMoney(payment.amount)}</span>
                  </li>
                ))}
          </ul>
        </section>
      )}
      {(page > 0 || state.items.length === PAGE_SIZE) && (
        <div className="account-actions">
          <button
            type="button"
            className="account-button account-button--secondary"
            disabled={page === 0}
            onClick={() => setPage((value) => value - 1)}
          >
            ‹ Trang trước
          </button>
          <button
            type="button"
            className="account-button account-button--secondary"
            disabled={state.items.length < PAGE_SIZE}
            onClick={() => setPage((value) => value + 1)}
          >
            Trang sau ›
          </button>
        </div>
      )}
      {selected && (
        <ConfirmRefundDialog
          refund={selected}
          onClose={() => setSelected(null)}
          onDone={() => {
            setSelected(null)
            setNotice(
              `Đã ghi nhận hoàn ${formatMoney(selected.amount)} cho GD${selected.paymentId}.`,
            )
            setReload((value) => value + 1)
          }}
        />
      )}
    </section>
  )
}
