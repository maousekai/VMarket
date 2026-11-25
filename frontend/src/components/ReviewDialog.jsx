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
 * Chi gui khi moi anh da tai xong, va dong hop thoai luc dang luu thi doi biet ket qua roi moi don
 * anh (luu thanh cong thi anh thuoc danh gia, khong duoc xoa).
 */
export default function ReviewDialog({ orderId, item, product, onClose, onCreated }) {
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [images, setImages] = useState([])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [uploading, setUploading] = useState(false)
  const uploaded = useRef(new Set())
  const submitted = useRef(false)
  const pendingSave = useRef(null)
  const closed = useRef(false)
  const variant = product?.variants?.find((entry) => entry.id === item.variantId)
  const image = imageUrlOrEmpty(product?.imageUrls?.[0])

  useEffect(() => {
    // StrictMode chay cleanup roi mount lai: hop thoai van dang mo.
    closed.current = false
    // Cung mot Set suot vong doi hop thoai (chi them phan tu), giu tham chieu cho cleanup.
    const urls = uploaded.current
    return () => {
      closed.current = true
      const cleanup = () => {
        if (!submitted.current) discardUploaded([...urls], deleteReviewImage)
      }
      // Dang luu: chua biet anh co nam trong danh gia hay khong -> doi ket qua.
      if (pendingSave.current) pendingSave.current.then(cleanup)
      else cleanup()
    }
  }, [])

  function onUploaded(urls) {
    // Anh tai xong sau khi hop thoai da dong (va khong co danh gia nao duoc luu): xoa ngay.
    if (closed.current && !submitted.current && !pendingSave.current) {
      discardUploaded(urls, deleteReviewImage)
      return
    }
    urls.forEach((url) => uploaded.current.add(url))
  }

  async function submit(event) {
    event.preventDefault()
    if (saving) return
    if (uploading) return setError('Ảnh đang được tải lên, vui lòng đợi tải xong rồi gửi.')
    if (!rating) return setError('Vui lòng chọn số sao cho sản phẩm.')
    setSaving(true)
    setError('')
    const save = createReview({
      orderId,
      productId: item.productId,
      rating,
      comment,
      imageUrls: images,
    })
    pendingSave.current = save.then(
      () => {
        submitted.current = true
      },
      (failure) => {
        if (getApiError(failure).code === 'REVIEW_ALREADY_EXISTS') submitted.current = true
        pendingSave.current = null
      },
    )
    try {
      const created = await save
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
          onUploaded={onUploaded}
          onUploadingChange={setUploading}
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
            disabled={saving}
            onClick={onClose}
          >
            Huỷ
          </button>
          <button type="submit" className="account-button" disabled={saving || uploading}>
            {saving ? 'Đang gửi…' : uploading ? 'Đang tải ảnh…' : 'Gửi đánh giá'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
