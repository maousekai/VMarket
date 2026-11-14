import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import Modal from '../components/Modal'
import Pagination from '../components/Pagination'
import { ReviewItem, StarRow } from '../components/ReviewParts'
import { formatRating } from '../utils/review'
import { useProductLookup } from '../hooks/useCatalogLookup'
import { getApiError } from '../services/api/apiClient'
import {
  deleteReviewReply,
  getShopRatingSummary,
  listSellerReviews,
  replyToReview,
  REVIEW_LIMITS,
  REVIEW_STATUS,
} from '../services/api/reviewApi'
import { getMyShop } from '../services/api/shopApi'
import { shortOrderId } from '../utils/order'
import { imageUrlOrEmpty } from '../utils/shop'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/seller.css'
import '../components/orders.css'
import '../components/reviews.css'

const PAGE_SIZE = 10
// Review Service loc so sao chinh xac (rating=n) va loc da/chua phan hoi (replied).
const TABS = [
  { key: 'all', label: 'Tất cả' },
  { key: '5', label: '5 sao', rating: 5 },
  { key: '4', label: '4 sao', rating: 4 },
  { key: '3', label: '3 sao', rating: 3 },
  { key: '2', label: '2 sao', rating: 2 },
  { key: '1', label: '1 sao', rating: 1 },
  { key: 'unreplied', label: 'Chưa phản hồi', replied: false },
]
const QUICK_REPLIES = [
  'Cảm ơn bạn đã tin tưởng và ủng hộ gian hàng!',
  'Gian hàng xin lỗi vì trải nghiệm chưa tốt, shop sẽ liên hệ hỗ trợ bạn sớm nhất.',
]

function ReplyForm({ review, onSaved, onCancel }) {
  const [content, setContent] = useState(review.reply?.content || '')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const editing = Boolean(review.reply)

  async function submit(event) {
    event.preventDefault()
    if (!content.trim()) return setError('Nội dung phản hồi không được để trống.')
    setSaving(true)
    setError('')
    try {
      onSaved(await replyToReview(review.id, content), editing)
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }

  return (
    <form className="seller-reply-form" noValidate onSubmit={submit}>
      <label>
        <span>{editing ? 'Sửa phản hồi công khai' : 'Phản hồi từ người bán (công khai)'}</span>
        <textarea
          maxLength={REVIEW_LIMITS.reply}
          value={content}
          disabled={saving}
          placeholder="Phản hồi sẽ hiển thị công khai dưới đánh giá trên trang sản phẩm."
          onChange={(event) => setContent(event.target.value)}
        />
      </label>
      {!editing && (
        <div className="review-filters" role="group" aria-label="Gợi ý nhanh">
          {QUICK_REPLIES.map((text) => (
            <button key={text} type="button" disabled={saving} onClick={() => setContent(text)}>
              {text.length > 40 ? `${text.slice(0, 40)}…` : text}
            </button>
          ))}
        </div>
      )}
      {error && (
        <p className="form-message form-message--error" role="alert">
          {error}
        </p>
      )}
      <div className="seller-reply-form__actions">
        {onCancel && (
          <button
            type="button"
            className="account-button account-button--secondary"
            disabled={saving}
            onClick={onCancel}
          >
            Huỷ
          </button>
        )}
        <button type="submit" className="account-button" disabled={saving}>
          {saving ? 'Đang gửi…' : editing ? 'Lưu phản hồi' : 'Gửi phản hồi công khai'}
        </button>
      </div>
    </form>
  )
}

function SellerReviewCard({ review, product, onChanged, onDeleteReply }) {
  const [editing, setEditing] = useState(false)
  // Bi admin an hoac don da tra hang / hoan tien: khong hien cong khai, khong phan hoi duoc.
  const hidden = review.status !== REVIEW_STATUS.visible
  const image = imageUrlOrEmpty(product?.imageUrls?.[0])
  const badge =
    review.status === REVIEW_STATUS.returned ? (
      <span className="review-badge review-badge--hidden">Đơn đã trả hàng</span>
    ) : hidden ? (
      <span className="review-badge review-badge--hidden">Đã bị ẩn</span>
    ) : review.reply ? (
      <span className="review-badge review-badge--done">Đã phản hồi</span>
    ) : (
      <span className="review-badge review-badge--pending">Chưa phản hồi</span>
    )

  return (
    <article className="account-card seller-review-card">
      <div className="seller-review-card__product">
        {image ? <img src={image} alt="" /> : <span className="review-form__thumb" />}
        <span>
          {product ? (
            <Link to={`/products/${encodeURIComponent(review.productId)}`}>{product.name}</Link>
          ) : (
            <strong>Sản phẩm</strong>
          )}
          {review.orderId && <small> · Đơn {shortOrderId(review.orderId)}</small>}
        </span>
      </div>
      {/* Phan hoi da co hien trong ReviewItem; khi dang sua thi an de khong lap noi dung. */}
      <ReviewItem review={editing ? { ...review, reply: null } : review} header={badge} />
      {hidden && review.moderationReason && (
        <p className="seller-order__hint">Lý do ẩn: {review.moderationReason}</p>
      )}
      {!hidden && !review.reply && (
        <ReplyForm review={review} onSaved={(saved) => onChanged(saved, false)} />
      )}
      {!hidden && review.reply && editing && (
        <ReplyForm
          review={review}
          onCancel={() => setEditing(false)}
          onSaved={(saved) => {
            setEditing(false)
            onChanged(saved, true)
          }}
        />
      )}
      {!hidden && review.reply && !editing && (
        <div className="review-actions">
          <button
            type="button"
            className="account-button account-button--secondary"
            onClick={() => setEditing(true)}
          >
            Chỉnh sửa phản hồi
          </button>
          <button
            type="button"
            className="account-button account-button--danger"
            onClick={() => onDeleteReply(review)}
          >
            Xoá phản hồi
          </button>
        </div>
      )}
    </article>
  )
}

/** [Seller] Quan ly danh gia & phan hoi cua gian hang (UC-14, FR-REV-03). */
export default function SellerReviewsPage() {
  const [params, setParams] = useSearchParams()
  const tab = TABS.find((entry) => entry.key === params.get('tab')) || TABS[0]
  const page = Math.max(0, Number.parseInt(params.get('page') || '0', 10) || 0)
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [stats, setStats] = useState(null)
  const [reload, setReload] = useState(0)
  const [notice, setNotice] = useState('')
  const [deleting, setDeleting] = useState(null)
  const [deleteError, setDeleteError] = useState('')

  const loadStats = useCallback(async () => {
    const [shop, unreplied] = await Promise.all([
      getMyShop(),
      listSellerReviews({ replied: false, page: 0, size: 1 }),
    ])
    const summary = await getShopRatingSummary(shop.id)
    setStats({ summary, unreplied: unreplied.totalElements })
  }, [])

  useEffect(() => {
    loadStats().catch(() => setStats(null))
  }, [loadStats, reload])

  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, status: 'loading', error: '' }))
    listSellerReviews({ rating: tab.rating, replied: tab.replied, page, size: PAGE_SIZE })
      .then((result) => current && setState({ status: 'ready', result, error: '' }))
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', result: null, error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [tab.key, tab.rating, tab.replied, page, reload])

  const result = state.result
  const products = useProductLookup(result?.items.map((review) => review.productId) || [])

  function update(changes) {
    setNotice('')
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([key, value]) =>
      value === null || value === undefined ? next.delete(key) : next.set(key, value),
    )
    setParams(next)
  }

  function changed(saved, edited) {
    setNotice(edited ? 'Đã cập nhật phản hồi.' : 'Đã gửi phản hồi công khai.')
    // Tab "Chua phan hoi": danh gia vua phan hoi phai roi khoi danh sach -> tai lai.
    if (tab.replied === false) setReload((value) => value + 1)
    else {
      setState((current) => ({
        ...current,
        result: current.result && {
          ...current.result,
          items: current.result.items.map((item) => (item.id === saved.id ? saved : item)),
        },
      }))
      loadStats().catch(() => {})
    }
  }

  async function confirmDelete() {
    setDeleteError('')
    try {
      await deleteReviewReply(deleting.id)
      setDeleting(null)
      setNotice('Đã xoá phản hồi.')
      setReload((value) => value + 1)
    } catch (failure) {
      setDeleteError(getApiError(failure).message)
    }
  }

  const summary = stats?.summary
  return (
    <section className="seller-page" aria-labelledby="seller-reviews-title">
      <div className="seller-page__header">
        <div>
          <span className="account-eyebrow">Kênh người bán</span>
          <h1 id="seller-reviews-title">Đánh giá & Phản hồi</h1>
        </div>
      </div>
      <div className="seller-reviews__stats">
        <div className="account-card seller-reviews__stat">
          <span>Đánh giá trung bình</span>
          <strong>{summary ? `${formatRating(summary.average)} / 5` : '—'}</strong>
          {summary && <StarRow value={summary.average} size={14} />}
        </div>
        <div className="account-card seller-reviews__stat">
          <span>Tổng lượt đánh giá</span>
          <strong>{summary ? summary.count : '—'}</strong>
        </div>
        <div className="account-card seller-reviews__stat seller-reviews__stat--warn">
          <span>Chưa phản hồi</span>
          <strong>{stats ? stats.unreplied : '—'}</strong>
        </div>
      </div>
      <div className="order-tabs" role="group" aria-label="Lọc đánh giá">
        {TABS.map((entry) => (
          <button
            key={entry.key}
            type="button"
            aria-pressed={tab.key === entry.key}
            onClick={() => update({ tab: entry.key === 'all' ? null : entry.key, page: null })}
          >
            {entry.label}
          </button>
        ))}
      </div>
      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
        </p>
      )}
      {state.status === 'loading' && !result && (
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
      {result && state.status !== 'error' && (
        <>
          {result.items.length === 0 ? (
            <div className="account-card account-state">
              <p>
                {tab.replied === false
                  ? 'Tất cả đánh giá đã được phản hồi.'
                  : 'Chưa có đánh giá nào ở mục này.'}
              </p>
            </div>
          ) : (
            <div className={`order-list${state.status === 'loading' ? ' is-loading' : ''}`}>
              {result.items.map((review) => (
                <SellerReviewCard
                  key={review.id}
                  review={review}
                  product={products[review.productId]?.value}
                  onChanged={changed}
                  onDeleteReply={(target) => {
                    setDeleteError('')
                    setDeleting(target)
                  }}
                />
              ))}
            </div>
          )}
          <Pagination
            page={result.page}
            totalPages={result.totalPages}
            onChange={(next) => update({ page: next ? String(next) : null })}
          />
        </>
      )}
      {deleting && (
        <Modal
          title="Xoá phản hồi?"
          description="Phản hồi sẽ không còn hiển thị dưới đánh giá trên trang sản phẩm."
          onClose={() => setDeleting(null)}
        >
          {deleteError && (
            <p className="form-message form-message--error" role="alert">
              {deleteError}
            </p>
          )}
          <div className="account-actions">
            <button
              type="button"
              className="account-button account-button--secondary"
              data-autofocus
              onClick={() => setDeleting(null)}
            >
              Giữ lại
            </button>
            <button
              type="button"
              className="account-button account-button--danger"
              onClick={confirmDelete}
            >
              Xoá phản hồi
            </button>
          </div>
        </Modal>
      )}
    </section>
  )
}
