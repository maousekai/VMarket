import { useEffect, useState } from 'react'
import Modal from '../components/Modal'
import { OrderItemsList, ReturnStatusBadge } from '../components/OrderParts'
import Pagination from '../components/Pagination'
import { getApiError } from '../services/api/apiClient'
import { listAdminReturns, resolveReturn } from '../services/api/orderApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/adminCatalog.css'
import '../components/orders.css'
import EvidenceGallery from '../components/EvidenceGallery'

const PAGE_SIZE = 10
const SELLER_RESPONSE_MS = 48 * 3600 * 1000
const TABS = [
  ['DISPUTED', 'Đang khiếu nại'],
  ['PENDING', 'Shop chưa phản hồi'],
  ['RESOLVED', 'Đã hoàn tiền'],
  ['ADMIN_REJECTED', 'Sàn từ chối'],
  ['', 'Tất cả'],
]

const isOverdue = (item, now = Date.now()) =>
  item.status === 'PENDING' && now - new Date(item.createdAt).getTime() >= SELLER_RESPONSE_MS

/** Admin chi phan xu duoc khieu nai, hoac yeu cau ma shop qua 48 gio khong phan hoi. */
const canResolve = (item) => item.status === 'DISPUTED' || isOverdue(item)

const refundOf = (item) =>
  item.refundAmount ?? (item.items || []).reduce((sum, line) => sum + Number(line.lineTotal), 0)

function ResolveDialog({ item, onClose, onDone }) {
  const [approved, setApproved] = useState(true)
  const [restock, setRestock] = useState(true)
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function submit(event) {
    event.preventDefault()
    if (!note.trim()) return setError('Vui lòng ghi kết luận để gửi cho hai bên.')
    setSaving(true)
    setError('')
    try {
      onDone(
        await resolveReturn(item.id, { approved, restock: approved && restock, note }),
        approved,
      )
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }

  return (
    <Modal
      title="Phân xử yêu cầu trả hàng"
      description={`Đơn ${shortOrderId(item.orderId)} · hoàn tối đa ${formatMoney(refundOf(item))}`}
      onClose={onClose}
    >
      <form className="admin-catalog__form" noValidate onSubmit={submit}>
        <fieldset className="admin-dispute__choice">
          <legend>Quyết định</legend>
          <label>
            <input type="radio" checked={approved} onChange={() => setApproved(true)} />
            Chấp nhận – hoàn tiền cho người mua
          </label>
          <label>
            <input type="radio" checked={!approved} onChange={() => setApproved(false)} />
            Từ chối – giữ nguyên đơn hàng
          </label>
        </fieldset>
        {approved && (
          <label className="admin-catalog__checkbox">
            <input
              type="checkbox"
              checked={restock}
              onChange={(event) => setRestock(event.target.checked)}
            />
            <span>Nhập lại hàng trả vào kho của gian hàng</span>
          </label>
        )}
        <label>
          <span>Kết luận *</span>
          <textarea
            data-autofocus
            rows={4}
            maxLength={500}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </label>
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
            {saving ? 'Đang lưu…' : 'Lưu quyết định'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

function DisputeCard({ item, onResolve }) {
  return (
    <article className="account-card order-card" aria-labelledby={`dispute-${item.id}`}>
      <header className="order-card__head">
        <div>
          <h2 id={`dispute-${item.id}`}>Trả hàng cho đơn {shortOrderId(item.orderId)}</h2>
          <small>
            Gửi lúc {formatDateTime(item.createdAt)}
            {isOverdue(item) && ' · Shop quá 48 giờ chưa phản hồi'}
          </small>
        </div>
        <ReturnStatusBadge status={item.status} />
      </header>
      <div className="seller-order__buyer">
        <span>Lý do người mua: {item.reason}</span>
        {item.sellerNote && <span>Phản hồi gian hàng: {item.sellerNote}</span>}
        {item.adminNote && <span>Kết luận của sàn: {item.adminNote}</span>}
      </div>
      <EvidenceGallery urls={item.evidenceUrls} className="seller-order__evidence" />
      <OrderItemsList items={item.items || []} />
      <footer className="order-card__foot">
        <span>
          Giá trị trả: <strong>{formatMoney(refundOf(item))}</strong>
        </span>
        {canResolve(item) && (
          <button type="button" className="account-button" onClick={() => onResolve(item)}>
            Phân xử
          </button>
        )}
      </footer>
    </article>
  )
}

export default function AdminDisputesPage() {
  const [filters, setFilters] = useState({ status: 'DISPUTED', page: 0 })
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [reload, setReload] = useState(0)
  const [selected, setSelected] = useState(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, status: 'loading' }))
    listAdminReturns({ ...filters, size: PAGE_SIZE })
      .then((result) => current && setState({ status: 'ready', result, error: '' }))
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', result: null, error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [filters, reload])

  const result = state.result
  return (
    <section className="admin-catalog" aria-labelledby="admin-disputes-heading">
      <div className="admin-users__header">
        <div>
          <span className="account-eyebrow">Chăm sóc khách hàng</span>
          <h1 id="admin-disputes-heading">Phân xử khiếu nại</h1>
          <p className="account-subtitle">
            Xử lý khiếu nại trả hàng bị gian hàng từ chối hoặc quá 48 giờ chưa phản hồi.
          </p>
        </div>
      </div>
      <div className="admin-users__toolbar">
        <div className="admin-users__tabs" role="group" aria-label="Lọc theo trạng thái">
          {TABS.map(([value, label]) => (
            <button
              key={label}
              type="button"
              className="admin-users__tab"
              aria-pressed={filters.status === value}
              onClick={() => setFilters({ status: value, page: 0 })}
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
      {state.status === 'loading' && !result && (
        <p className="account-notice" role="status">
          Đang tải…
        </p>
      )}
      {result && state.status !== 'error' && (
        <>
          {result.content.length === 0 ? (
            <p className="admin-users__empty">Không có yêu cầu nào ở trạng thái này.</p>
          ) : (
            <div className={`order-list${state.status === 'loading' ? ' is-loading' : ''}`}>
              {result.content.map((item) => (
                <DisputeCard key={item.id} item={item} onResolve={setSelected} />
              ))}
            </div>
          )}
          <Pagination
            page={result.page}
            totalPages={result.totalPages}
            onChange={(page) => setFilters({ ...filters, page })}
          />
        </>
      )}
      {selected && (
        <ResolveDialog
          item={selected}
          onClose={() => setSelected(null)}
          onDone={(_, approved) => {
            setSelected(null)
            setNotice(
              approved ? 'Đã chấp nhận hoàn tiền cho người mua.' : 'Đã từ chối yêu cầu trả hàng.',
            )
            setReload((value) => value + 1)
          }}
        />
      )}
    </section>
  )
}
