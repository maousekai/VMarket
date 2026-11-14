import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AccountLayout from '../components/AccountLayout'
import { getApiError } from '../services/api/apiClient'
import { listMyPayments } from '../services/api/paymentApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { PAYMENT_METHOD_LABELS, shortOrderId } from '../utils/order'
import { paymentCode, PAYMENT_STATUS_LABELS, paymentTone } from '../utils/payment'
import '../components/orders.css'
import '../components/payment.css'

const PAGE_SIZE = 20

export function PaymentStatusBadge({ status }) {
  return (
    <span className={`payment-status payment-status--${paymentTone(status)}`}>
      {PAYMENT_STATUS_LABELS[status] || status}
    </span>
  )
}

/** Lich su giao dich cua nguoi mua (moi nhat truoc), tai them theo trang. */
export default function PaymentHistoryPage() {
  const [state, setState] = useState({ status: 'loading', items: [], hasMore: false, error: '' })
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let current = true
    setState((value) => ({ ...value, status: 'loading', error: '' }))
    listMyPayments({ page, size: PAGE_SIZE })
      .then((items) => {
        if (!current) return
        setState((value) => ({
          status: 'ready',
          items: page === 0 ? items : [...value.items, ...items],
          hasMore: items.length === PAGE_SIZE,
          error: '',
        }))
      })
      .catch(
        (failure) =>
          current &&
          setState((value) => ({ ...value, status: 'error', error: getApiError(failure).message })),
      )
    return () => {
      current = false
    }
  }, [page, reload])

  return (
    <AccountLayout title="Lịch sử giao dịch" current="/profile/payments">
      <div className="orders-page">
        <div className="orders-page__head">
          <div>
            <h1 id="payments-title">Lịch sử giao dịch</h1>
            <p className="account-subtitle">Các khoản thanh toán và hoàn tiền của đơn hàng.</p>
          </div>
        </div>
        <section className="account-card" aria-labelledby="payments-title">
          {state.status === 'loading' && !state.items.length && (
            <p className="account-notice" role="status">
              Đang tải giao dịch…
            </p>
          )}
          {state.status === 'error' && (
            <div className="account-state">
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
            <div className="account-state">
              <p>Bạn chưa có giao dịch nào.</p>
              <Link className="account-button" to="/">
                Mua sắm ngay
              </Link>
            </div>
          )}
          {state.items.length > 0 && (
            <ul className="payment-list">
              {state.items.map((payment) => (
                <li key={payment.id}>
                  <Link className="payment-row" to={`/profile/payments/${payment.id}`}>
                    <span>
                      <strong>
                        {paymentCode(payment)} · Đơn {shortOrderId(payment.orderId)}
                      </strong>
                      <small>
                        {PAYMENT_METHOD_LABELS[payment.method] || payment.method} ·{' '}
                        {formatDateTime(payment.paidAt || payment.createdAt)}
                      </small>
                    </span>
                    <PaymentStatusBadge status={payment.status} />
                    <span className="payment-row__amount">{formatMoney(payment.amount)}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
          {state.hasMore && (
            <div className="account-actions">
              <button
                type="button"
                className="account-button account-button--secondary"
                disabled={state.status === 'loading'}
                onClick={() => setPage((value) => value + 1)}
              >
                {state.status === 'loading' ? 'Đang tải…' : 'Xem thêm'}
              </button>
            </div>
          )}
        </section>
      </div>
    </AccountLayout>
  )
}
