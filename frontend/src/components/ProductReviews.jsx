import { useCallback, useState } from 'react'
import Pagination from './Pagination'
import { RatingSummaryCard, ReviewItem } from './ReviewParts'
import SectionError from './SectionError'
import useRemoteData from '../hooks/useRemoteData'
import { getProductRatingSummary, getProductReviews } from '../services/api/reviewApi'
import './reviews.css'

const PAGE_SIZE = 5

/**
 * "Danh gia khach hang" tren trang san pham (FR-REV-02): diem trung binh, phan bo sao, loc theo sao
 * va danh sach (moi nhat truoc) kem phan hoi cua nguoi ban.
 */
export default function ProductReviews({ productId }) {
  const [rating, setRating] = useState()
  const [page, setPage] = useState(0)

  const loadSummary = useCallback(() => getProductRatingSummary(productId), [productId])
  const summary = useRemoteData(loadSummary, productId)
  const loadPage = useCallback(
    () => getProductReviews(productId, { rating, page, size: PAGE_SIZE }),
    [productId, rating, page],
  )
  const reviews = useRemoteData(loadPage, `${productId}:${rating ?? 'all'}:${page}`)

  const count = Number(summary.data?.count) || 0
  return (
    <section className="pdp-card pdp-reviews" aria-labelledby="pdp-reviews-title">
      <h2 id="pdp-reviews-title">Đánh giá khách hàng</h2>
      {summary.status === 'error' && (
        <SectionError what="đánh giá" error={summary.error} onRetry={summary.reload} />
      )}
      {summary.status === 'loading' && (
        <p className="section-state" role="status">
          Đang tải đánh giá…
        </p>
      )}
      {summary.status === 'ready' && count === 0 && (
        <p className="review-empty">
          Sản phẩm chưa có đánh giá. Hãy là người đầu tiên đánh giá sau khi nhận hàng.
        </p>
      )}
      {summary.status === 'ready' && count > 0 && (
        <>
          <RatingSummaryCard
            summary={summary.data}
            selected={rating}
            onSelect={(star) => {
              setRating(star)
              setPage(0)
            }}
          />
          {reviews.status === 'loading' && (
            <p className="section-state" role="status">
              Đang tải đánh giá…
            </p>
          )}
          {reviews.status === 'error' && (
            <SectionError what="đánh giá" error={reviews.error} onRetry={reviews.reload} />
          )}
          {reviews.status === 'ready' && reviews.data.items.length === 0 && (
            <p className="review-empty">Chưa có đánh giá {rating} sao.</p>
          )}
          {reviews.status === 'ready' && reviews.data.items.length > 0 && (
            <>
              <ul className="review-list">
                {reviews.data.items.map((review) => (
                  <li key={review.id}>
                    <ReviewItem review={review} />
                  </li>
                ))}
              </ul>
              <Pagination
                page={reviews.data.page}
                totalPages={reviews.data.totalPages}
                onChange={setPage}
                label="Trang đánh giá"
              />
            </>
          )}
        </>
      )}
    </section>
  )
}
