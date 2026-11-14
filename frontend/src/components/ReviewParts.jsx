import { useState } from 'react'
import StarIcon from './StarIcon'
import { formatDateTime } from '../utils/formatDate'
import { formatRating, RATING_LABELS } from '../utils/review'
import './reviews.css'

/** Hang sao chi de xem; `value` co the le (4.5) — sao duoc to theo phan nguyen gan nhat. */
export function StarRow({ value = 0, size = 14, label }) {
  const filled = Math.round(Number(value) || 0)
  return (
    <span
      className="review-stars"
      role="img"
      aria-label={label || `${formatRating(value)} trên 5 sao`}
    >
      {Array.from({ length: 5 }, (_, index) => (
        <StarIcon key={index} size={size} className={index < filled ? '' : 'is-empty'} />
      ))}
    </span>
  )
}

/** Chon 1–5 sao bang chuot hoac ban phim (nhom radio). */
export function StarRatingInput({ value, onChange, disabled = false, name = 'rating' }) {
  const [hover, setHover] = useState(0)
  const shown = hover || value
  return (
    <div className="review-star-input" role="radiogroup" aria-label="Số sao đánh giá">
      {[1, 2, 3, 4, 5].map((star) => (
        <label
          key={star}
          className={star <= shown ? 'is-on' : ''}
          onMouseEnter={() => setHover(star)}
          onMouseLeave={() => setHover(0)}
        >
          <input
            type="radio"
            name={name}
            value={star}
            checked={value === star}
            disabled={disabled}
            onChange={() => onChange(star)}
          />
          <StarIcon size={32} />
          <span className="visually-hidden">
            {star} sao – {RATING_LABELS[star]}
          </span>
        </label>
      ))}
    </div>
  )
}

/** Anh dinh kem: bam de xem anh goc o tab moi. */
export function ReviewImages({ urls = [] }) {
  if (!urls.length) return null
  return (
    <ul className="review-images" aria-label="Ảnh đính kèm">
      {urls.map((url, index) => (
        <li key={url}>
          <a href={url} target="_blank" rel="noreferrer">
            <img src={url} alt={`Ảnh đánh giá ${index + 1}`} loading="lazy" />
          </a>
        </li>
      ))}
    </ul>
  )
}

/**
 * Mot danh gia. Danh sach cong khai khong tra ten nguoi mua (Review Service an buyerId de bao ve
 * quyen rieng tu) nen moi danh gia hien "Nguoi mua da xac minh".
 */
export function ReviewItem({ review, children, header }) {
  return (
    <article className="review-item">
      <div className="review-item__head">
        <span className="review-item__avatar" aria-hidden="true">
          <StarIcon size={14} />
        </span>
        <div>
          <strong>Người mua đã xác minh</strong>
          <div className="review-item__meta">
            <StarRow value={review.rating} size={13} />
            <span>{formatDateTime(review.createdAt)}</span>
          </div>
        </div>
        {header}
      </div>
      {review.comment && <p className="review-item__comment">{review.comment}</p>}
      <ReviewImages urls={review.imageUrls} />
      {review.reply && (
        <div className="review-reply">
          <strong>Phản hồi của người bán</strong>
          <p>{review.reply.content}</p>
          <small>{formatDateTime(review.reply.updatedAt || review.reply.createdAt)}</small>
        </div>
      )}
      {children}
    </article>
  )
}

/** Diem trung binh + phan bo sao (RatingSummary cua Review Service). */
export function RatingSummaryCard({ summary, selected, onSelect }) {
  const count = Number(summary?.count) || 0
  return (
    <div className="rating-summary">
      <div className="rating-summary__score">
        <strong>{formatRating(summary?.average)}</strong>
        <span>/ 5</span>
        <StarRow value={summary?.average} size={16} />
        <small>{count.toLocaleString('vi-VN')} đánh giá</small>
      </div>
      <ul className="rating-summary__bars">
        {[5, 4, 3, 2, 1].map((star) => {
          const value = Number(summary?.distribution?.[star]) || 0
          const percent = count ? Math.round((value / count) * 100) : 0
          return (
            <li key={star}>
              <span>{star} sao</span>
              <span className="rating-summary__bar" aria-hidden="true">
                <span style={{ width: `${percent}%` }} />
              </span>
              <span className="rating-summary__count">{value}</span>
            </li>
          )
        })}
      </ul>
      {onSelect && (
        <div className="review-filters" role="group" aria-label="Lọc theo số sao">
          {[undefined, 5, 4, 3, 2, 1].map((star) => (
            <button
              key={star ?? 'all'}
              type="button"
              aria-pressed={selected === star}
              onClick={() => onSelect(star)}
            >
              {star ? `${star} sao` : 'Tất cả'}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
