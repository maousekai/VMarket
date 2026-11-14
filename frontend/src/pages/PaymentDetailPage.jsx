import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import AccountLayout from '../components/AccountLayout'
import { getApiError } from '../services/api/apiClient'
import { getOrder, listOrderReturns } from '../services/api/orderApi'
import { getPayment, listPaymentRefunds } from '../services/api/paymentApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import {
  canRequestReturn,
  ORDER_STATUS_LABELS,
  PAYMENT_METHOD_LABELS,
  shortOrderId,
} from '../utils/order'
import { isAwaitingPayment, paymentCode, REFUND_STATUS_LABELS } from '../utils/payment'
import { PaymentStatusBadge } from './PaymentHistoryPage'
import '../components/orders.css'
import '../components/payment.css'

const orderLink = (orderId) => `/profile/orders/${encodeURIComponent(orderId)}`

function refundSource(refund) {
  if (refund.returnId?.startsWith('CANCELLATION_')) return 'Đơn đã thanh toán bị huỷ'
  if (refund.returnId) return 'Yêu cầu trả hàng được chấp nhận'
  return refund.reason
}

/** Chi tiet giao dich: trang thai, moc thoi gian, cac khoan hoan tien va thao tac lien quan. */
export default function PaymentDetailPage() {
  const { paymentId } = useParams()
  const [state, setState] = useState({ status: 'loading', error: '' })

  useEffect(() => {
    let current = true
    setState({ status: 'loading', error: '' })
    ;(async () => {
      const payment = await getPayment(paymentId)
      const [refunds, order, returns] = await Promise.all([
        listPaymentRefunds(paymentId).catch(() => []),
        getOrder(payment.orderId).catch(() => null),
        listOrderReturns(payment.orderId).catch(() => []),
      ])
      return { status: 'ready', payment, refunds, order, returns, error: '' }
    })()
      .then((next) => current && setState(next))
      .catch(
        (failure) => current && setState({ status: 'error', error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [paymentId])

  let body
  if (state.status === 'loading') {
    body = (
      <p className="account-notice" role="status">
        Đang tải giao dịch…
      </p>
    )
  } else if (state.status === 'error') {
    body = (
      <div className="account-card account-state">
        <p role="alert">{state.error}</p>
        <Link className="account-button" to="/profile/payments">
          Về lịch sử giao dịch
        </Link>
      </div>
    )
  } else {
    const { payment, refunds, order, returns } = state
    const refunded = refunds
      .filter((refund) => refund.status === 'COMPLETED')
      .reduce((sum, refund) => sum + Number(refund.amount), 0)
    const canReturn = Boolean(payment.paidAt) && order && canRequestReturn(order, returns)
    const steps = [
      { done: true, label: 'Tạo giao dịch', at: payment.createdAt },
      payment.paidAt
        ? {
            done: true,
            label: payment.method === 'COD' ? 'Đã thu tiền khi giao' : 'Thanh toán thành công',
            at: payment.paidAt,
          }
        : {
            done: false,
            label:
              payment.method === 'COD'
                ? 'Thanh toán cho shipper khi nhận hàng'
                : `Chờ thanh toán${payment.expiresAt ? ` trước ${formatDateTime(payment.expiresAt)}` : ''}`,
          },
      ...refunds.map((refund) => ({
        done: refund.status === 'COMPLETED',
        label: `Hoàn ${formatMoney(refund.amount)} · ${REFUND_STATUS_LABELS[refund.status] || refund.status}`,
        at: refund.completedAt || refund.requestedAt,
      })),
    ]
    body = (
      <>
        <div className="account-card order-detail__head">
          <div>
            <Link className="link-button" to="/profile/payments">
              ‹ Lịch sử giao dịch
            </Link>
            <h1>Giao dịch {paymentCode(payment)}</h1>
            <small>
              Đơn {shortOrderId(payment.orderId)}
              {order && ` · ${ORDER_STATUS_LABELS[order.status] || order.status}`}
            </small>
          </div>
          <PaymentStatusBadge status={payment.status} />
        </div>

        <section className="account-card order-detail__section" aria-labelledby="payment-info">
          <h2 id="payment-info">Thông tin thanh toán</h2>
          <dl className="payment-facts">
            <div>
              <dt>Số tiền</dt>
              <dd className="payment-facts__amount">{formatMoney(payment.amount)}</dd>
            </div>
            <div>
              <dt>Phương thức</dt>
              <dd>{PAYMENT_METHOD_LABELS[payment.method] || payment.method}</dd>
            </div>
            {payment.paidAt && (
              <div>
                <dt>Thanh toán lúc</dt>
                <dd>{formatDateTime(payment.paidAt)}</dd>
              </div>
            )}
            {refunded > 0 && (
              <div>
                <dt>Đã hoàn</dt>
                <dd>{formatMoney(refunded)}</dd>
              </div>
            )}
          </dl>
          <div className="order-detail__actions">
            {isAwaitingPayment(payment) && order?.status === 'WAITING_PAYMENT' && (
              <Link
                className="account-button"
                to={`/payment/${encodeURIComponent(payment.orderId)}`}
              >
                Tiếp tục thanh toán
              </Link>
            )}
            {canReturn && (
              <Link
                className="account-button account-button--secondary"
                to={orderLink(payment.orderId)}
                state={{ openReturn: true }}
              >
                Yêu cầu trả hàng / hoàn tiền
              </Link>
            )}
            <Link
              className="account-button account-button--secondary"
              to={orderLink(payment.orderId)}
            >
              Xem đơn hàng
            </Link>
          </div>
        </section>

        <section className="account-card order-detail__section" aria-labelledby="payment-progress">
          <h2 id="payment-progress">Tiến trình</h2>
          <ol className="payment-timeline">
            {steps.map((step, index) => (
              <li key={index} className={step.done ? 'is-done' : ''}>
                <span>
                  <strong>{step.label}</strong>
                  {step.at && <small>{formatDateTime(step.at)}</small>}
                </span>
              </li>
            ))}
          </ol>
        </section>

        <section className="account-card order-detail__section" aria-labelledby="payment-refunds">
          <h2 id="payment-refunds">Hoàn tiền</h2>
          {refunds.length === 0 ? (
            <p className="order-detail__hint">
              {payment.paidAt
                ? 'Chưa có khoản hoàn tiền. Tiền được hoàn khi đơn đã thanh toán bị huỷ hoặc yêu cầu trả hàng được chấp nhận.'
                : 'Giao dịch chưa thanh toán nên chưa có khoản hoàn tiền.'}
            </p>
          ) : (
            <ul className="payment-list">
              {refunds.map((refund) => (
                <li key={refund.id} className="payment-row">
                  <span>
                    <strong>{refundSource(refund)}</strong>
                    <small>
                      Yêu cầu lúc {formatDateTime(refund.requestedAt)}
                      {refund.transferReference && ` · Mã chuyển khoản ${refund.transferReference}`}
                    </small>
                  </span>
                  <span
                    className={`payment-status payment-status--${refund.status === 'COMPLETED' ? 'success' : 'warning'}`}
                  >
                    {REFUND_STATUS_LABELS[refund.status] || refund.status}
                  </span>
                  <span className="payment-row__amount">{formatMoney(refund.amount)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </>
    )
  }

  return (
    <AccountLayout title="Chi tiết giao dịch" current="/profile/payments">
      <div className="order-detail">{body}</div>
    </AccountLayout>
  )
}
