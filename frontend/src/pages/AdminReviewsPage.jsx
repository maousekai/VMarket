import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import Modal from '../components/Modal'
import Pagination from '../components/Pagination'
import { ReviewImages, StarRow } from '../components/ReviewParts'
import { useProductLookup, useShopLookup } from '../hooks/useCatalogLookup'
import { getApiError } from '../services/api/apiClient'
import {
  hideReview,
  restoreReview,
  REVIEW_LIMITS,
  REVIEW_STATUS,
  searchReviews,
} from '../services/api/reviewApi'
import { formatDateTime } from '../utils/formatDate'
import { shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/sellerProducts.css'
import '../components/adminCatalog.css'
import '../components/reviews.css'

const PAGE_SIZE = 20
const TABS = [
  [REVIEW_STATUS.visible, 'Đang hiển thị'],
  [REVIEW_STATUS.hidden, 'Đã ẩn'],
  [REVIEW_STATUS.returned, 'Đơn đã trả hàng'],
  ['', 'Tất cả'],
]

function ModerationDialog({ review, onClose, onDone }) {
  const hiding = review.status !== REVIEW_STATUS.hidden
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function onSubmit(event) {
    event.preventDefault()
    if (hiding && !reason.trim()) return setError('Vui lòng nêu lý do ẩn đánh giá.')
    setSaving(true)
    setError('')
    try {
      onDone(hiding ? await hideReview(review.id, reason) : await restoreReview(review.id), hiding)
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }

  return (
    <Modal
      title={hiding ? 'Ẩn đánh giá vi phạm' : 'Khôi phục đánh giá'}
      description={
        hiding
          ? 'Đánh giá bị ẩn không hiển thị công khai và không được tính vào điểm trung bình.'
          : 'Đánh giá sẽ hiển thị lại và được tính vào điểm trung bình của sản phẩm.'
      }
      onClose={onClose}
    >
      <form className="admin-catalog__form" noValidate onSubmit={onSubmit}>
        {hiding && (
          <label>
            <span>Lý do ẩn *</span>
            <textarea
              data-autofocus
              rows={3}
              maxLength={REVIEW_LIMITS.hideReason}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
        )}
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
          <button
            type="submit"
            className={`account-button${hiding ? ' account-button--danger' : ''}`}
            disabled={saving}
          >
            {saving ? 'Đang lưu…' : hiding ? 'Ẩn đánh giá' : 'Khôi phục'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

/** [Admin] Kiem duyet danh gia (FR-REV-04): an danh gia vi pham (bat buoc ly do) va khoi phuc. */
export default function AdminReviewsPage() {
  const [status, setStatus] = useState(REVIEW_STATUS.visible)
  const [productId, setProductId] = useState('')
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [selected, setSelected] = useState(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, status: 'loading', error: '' }))
    searchReviews({ status, productId: filter, page, size: PAGE_SIZE })
      .then((result) => current && setState({ status: 'ready', result, error: '' }))
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', result: null, error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [status, filter, page, reload])

  const rows = state.result?.items || []
  const products = useProductLookup(rows.map((review) => review.productId))
  const shops = useShopLookup(rows.map((review) => review.shopId))

  return (
    <section className="admin-catalog" aria-labelledby="admin-reviews-heading">
      <div className="admin-users__header">
        <div>
          <span className="account-eyebrow">Kiểm duyệt nội dung</span>
          <h1 id="admin-reviews-heading">Kiểm duyệt đánh giá</h1>
          <p className="account-subtitle">
            Ẩn đánh giá vi phạm quy định cộng đồng và khôi phục khi xác minh không vi phạm.
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
              aria-pressed={status === value}
              onClick={() => {
                setStatus(value)
                setPage(0)
              }}
            >
              {label}
            </button>
          ))}
        </div>
        <form
          className="admin-users__search"
          role="search"
          onSubmit={(event) => {
            event.preventDefault()
            setFilter(productId.trim())
            setPage(0)
          }}
        >
          <input
            type="search"
            aria-label="Lọc theo mã sản phẩm"
            placeholder="Nhập mã sản phẩm rồi Enter…"
            value={productId}
            onChange={(event) => setProductId(event.target.value)}
          />
        </form>
      </div>
      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
        </p>
      )}
      {state.status === 'loading' && !state.result && (
        <p className="account-notice" role="status">
          Đang tải đánh giá…
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
      {state.status === 'ready' && rows.length === 0 && (
        <p className="admin-users__empty">Không có đánh giá phù hợp.</p>
      )}
      {rows.length > 0 && state.status !== 'error' && (
        <div className="admin-users__table-wrap">
          <table className="admin-table">
            <caption className="visually-hidden">Đánh giá cần kiểm duyệt</caption>
            <thead>
              <tr>
                <th scope="col">Sản phẩm & gian hàng</th>
                <th scope="col">Nội dung & sao</th>
                <th scope="col">Ngày</th>
                <th scope="col">Trạng thái</th>
                <th scope="col">
                  <span className="visually-hidden">Thao tác</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((review) => {
                const product = products[review.productId]?.value
                const shop = shops[review.shopId]?.value
                const hidden = review.status === REVIEW_STATUS.hidden
                const returned = review.status === REVIEW_STATUS.returned
                return (
                  <tr key={review.id}>
                    <td>
                      <div>
                        <strong>{product?.name || review.productId}</strong>
                        <small className="seller-products__muted">
                          <Link to={`/shops/${encodeURIComponent(review.shopId)}`}>
                            {shop?.name || review.shopId}
                          </Link>
                          {review.orderId && ` · Đơn ${shortOrderId(review.orderId)}`}
                        </small>
                      </div>
                    </td>
                    <td>
                      <StarRow value={review.rating} size={12} />
                      {review.comment && <p className="review-item__comment">{review.comment}</p>}
                      <ReviewImages urls={review.imageUrls} />
                    </td>
                    <td>{formatDateTime(review.createdAt)}</td>
                    <td>
                      {returned || hidden ? (
                        <>
                          <span className="review-badge review-badge--hidden">
                            {returned ? 'Đơn đã trả hàng' : 'Đã ẩn'}
                          </span>
                          <small className="seller-products__muted">
                            {review.moderationReason}
                          </small>
                        </>
                      ) : (
                        <span className="review-badge review-badge--done">Đang hiển thị</span>
                      )}
                    </td>
                    <td>
                      {/* Danh gia cua don da tra hang / hoan tien khong an / khoi phuc duoc. */}
                      {!returned && (
                        <div className="admin-catalog__actions">
                          <button
                            type="button"
                            className={`link-button${hidden ? '' : ' admin-catalog__danger'}`}
                            onClick={() => setSelected(review)}
                          >
                            {hidden ? 'Khôi phục' : 'Ẩn'}
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {state.result && (
        <Pagination
          page={state.result.page}
          totalPages={state.result.totalPages}
          onChange={setPage}
        />
      )}

      {selected && (
        <ModerationDialog
          review={selected}
          onClose={() => setSelected(null)}
          onDone={(_, hidden) => {
            setSelected(null)
            setNotice(hidden ? 'Đã ẩn đánh giá.' : 'Đã khôi phục đánh giá.')
            setReload((value) => value + 1)
          }}
        />
      )}
    </section>
  )
}
