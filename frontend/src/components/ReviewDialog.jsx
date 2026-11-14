import { useEffect, useRef, useState } from 'react'
import Modal from './Modal'
import ProductImagesField from './ProductImagesField'
import { StarRatingInput } from './ReviewParts'
import { RATING_LABELS } from '../utils/review'
import { getApiError } from '../services/api/apiClient'
import { discardUploaded } from '../services/api/mediaApi'
import {
  createReview,
  deleteReviewImage,
  REVIEW_LIMITS,
  uploadReviewImage,
} from '../services/api/reviewApi'
import { imageUrlOrEmpty } from '../utils/shop'
import { describeVariant } from '../utils/variant'
import './reviews.css'
import './sellerProducts.css'

/**
 * Viet danh gia cho mot dong hang cua don da giao / hoan tat (FR-REV-01): 1–5 sao, binh luan tuy
 * chon, toi da 5 anh. Anh tai len ngay khi chon; anh khong duoc gui kem bi xoa khi dong hop thoai.
 */
export default function ReviewDialog({ orderId, item, product, onClose, onCreated }) {
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [images, setImages] = useState([])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const uploaded = useRef(new Set())
  const submitted = useRef(false)
  const variant = product?.variants?.find((entry) => entry.id === item.variantId)
  const image = imageUrlOrEmpty(product?.imageUrls?.[0])

  useEffect(
    () => () => {
      if (!submitted.current) discardUploaded([...uploaded.current], deleteReviewImage)
    },
    [],
  )

  async function submit(event) {
    event.preventDefault()
    if (!rating) return setError('Vui lòng chọn số sao cho sản phẩm.')
    setSaving(true)
    setError('')
    try {
      const created = await createReview({
        orderId,
        productId: item.productId,
        rating,
        comment,
        imageUrls: images,
      })
      submitted.current = true
      discardUploaded(
        [...uploaded.current].filter((url) => !images.includes(url)),
        deleteReviewImage,
      )
      onCreated(created)
    } catch (failure) {
      const problem = getApiError(failure)
      if (problem.code === 'REVIEW_ALREADY_EXISTS') {
        // Da danh gia o tab khac / lan bam truoc: bao cho trang cap nhat trang thai.
        submitted.current = true
        onCreated(null, problem.message)
        return
      }
      setError(problem.message)
      setSaving(false)
    }
  }

  return (
    <Modal
      title="Đánh giá sản phẩm đã mua"
      description="Nhận xét của bạn giúp người mua khác chọn được sản phẩm phù hợp."
      onClose={onClose}
    >
      <form className="review-form" noValidate onSubmit={submit}>
        <div className="review-form__product">
          {image ? <img src={image} alt="" /> : <span className="review-form__thumb" />}
          <span>
            <strong>{product?.name || 'Sản phẩm'}</strong>
            {variant && <small>{describeVariant(variant)}</small>}
          </span>
        </div>
        <div className="review-form__rating">
          <p id="review-rating-label">Bạn cảm thấy sản phẩm này thế nào?</p>
          <StarRatingInput value={rating} onChange={setRating} disabled={saving} />
          <span className="review-form__label" aria-live="polite">
            {rating ? RATING_LABELS[rating] : 'Chọn số sao'}
          </span>
        </div>
        <label>
          <span>
            Nhận xét chi tiết <small>(không bắt buộc)</small>
          </span>
          <textarea
            maxLength={REVIEW_LIMITS.comment}
            placeholder="Chất lượng, đóng gói, thời gian giao hàng…"
            value={comment}
            disabled={saving}
            onChange={(event) => setComment(event.target.value)}
          />
          <small className="review-form__counter">
            {comment.length}/{REVIEW_LIMITS.comment}
          </small>
        </label>
        <ProductImagesField
          label="Thêm ảnh thực tế"
          max={REVIEW_LIMITS.images}
          required={false}
          showCover={false}
          value={images}
          onChange={(updater) =>
            setImages((current) => (typeof updater === 'function' ? updater(current) : updater))
          }
          onUploaded={(urls) => urls.forEach((url) => uploaded.current.add(url))}
          upload={uploadReviewImage}
          disabled={saving}
        />
        <p className="review-form__note">
          Mỗi sản phẩm trong một đơn chỉ được đánh giá một lần. Đánh giá vi phạm quy định có thể bị
          ẩn.
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
            {saving ? 'Đang gửi…' : 'Gửi đánh giá'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
