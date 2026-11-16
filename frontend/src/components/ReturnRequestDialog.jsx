import { useEffect, useRef, useState } from 'react'
import Modal from './Modal'
import ProductImagesField from './ProductImagesField'
import { useProductLookup } from '../hooks/useCatalogLookup'
import { getApiError } from '../services/api/apiClient'
import { discardUploaded } from '../services/api/mediaApi'
import { createReturn, deleteReturnEvidence, uploadReturnEvidence } from '../services/api/orderApi'
import { formatMoney } from '../utils/formatMoney'
import './sellerProducts.css'

const RETURN_REASONS = [
  'Sản phẩm bị lỗi / hư hỏng',
  'Giao sai sản phẩm hoặc sai phân loại',
  'Sản phẩm không đúng mô tả',
  'Thiếu hàng / thiếu phụ kiện',
]
const OTHER = 'Khác'
const REASON_MAX = 500
const EVIDENCE_MAX = 5

/**
 * Yeu cau tra hang / hoan tien cho TOAN BO don (moi don thuoc mot gian hang): gui tat ca dong
 * hang voi du so luong da mua. Minh chung toi da 5 tep, trong do toi da 1 video.
 */
export default function ReturnRequestDialog({ order, items, onClose, onCreated }) {
  const products = useProductLookup(items.map((item) => item.productId))
  const [reason, setReason] = useState(RETURN_REASONS[0])
  const [otherReason, setOtherReason] = useState('')
  const [evidence, setEvidence] = useState([])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const uploaded = useRef(new Set())
  const submitted = useRef(false)
  const refund = items.reduce((sum, item) => sum + Number(item.unitPrice) * item.quantity, 0)

  useEffect(
    () => () => {
      if (!submitted.current) discardUploaded([...uploaded.current], deleteReturnEvidence)
    },
    [],
  )

  async function submit(event) {
    event.preventDefault()
    const finalReason = reason === OTHER ? otherReason.trim() : reason
    if (!finalReason) return setError('Vui lòng mô tả lý do trả hàng.')
    if (!evidence.length) return setError('Vui lòng thêm ít nhất 1 ảnh hoặc video minh chứng.')
    setSaving(true)
    setError('')
    try {
      const created = await createReturn(order.id, {
        reason: finalReason,
        evidenceUrls: evidence,
        items: items.map((item) => ({
          orderItemId: item.id,
          productId: item.productId,
          variantId: item.variantId || null,
          quantity: item.quantity,
        })),
      })
      submitted.current = true
      discardUploaded(
        [...uploaded.current].filter((url) => !evidence.includes(url)),
        deleteReturnEvidence,
      )
      onCreated(created)
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }

  return (
    <Modal
      title="Yêu cầu trả hàng / hoàn tiền"
      description="Yêu cầu áp dụng cho toàn bộ đơn hàng. Gian hàng phản hồi trong 48 giờ."
      onClose={onClose}
    >
      <form className="return-form" noValidate onSubmit={submit}>
        <fieldset>
          <legend>Sản phẩm trả lại</legend>
          <ul className="return-form__items">
            {items.map((item) => {
              const product = products[item.productId]?.value
              const variant = product?.variants?.find((entry) => entry.id === item.variantId)
              return (
                <li key={item.id}>
                  <span>
                    <strong>{product?.name || 'Sản phẩm'}</strong>
                    <small>
                      {[
                        variant && Object.values(variant.attributes || {}).join(' · '),
                        `${formatMoney(item.unitPrice)} × ${item.quantity}`,
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </small>
                  </span>
                </li>
              )
            })}
          </ul>
          <p className="return-form__refund">
            Số tiền hoàn dự kiến: <strong>{formatMoney(refund)}</strong>
          </p>
        </fieldset>
        <fieldset>
          <legend>Lý do</legend>
          <div className="return-form__reasons">
            {[...RETURN_REASONS, OTHER].map((option) => (
              <label key={option}>
                <input
                  type="radio"
                  name="return-reason"
                  checked={reason === option}
                  onChange={() => setReason(option)}
                />
                {option}
              </label>
            ))}
          </div>
          {reason === OTHER && (
            <textarea
              aria-label="Mô tả lý do khác"
              rows={3}
              maxLength={REASON_MAX}
              placeholder="Mô tả vấn đề bạn gặp phải"
              value={otherReason}
              onChange={(event) => setOtherReason(event.target.value)}
            />
          )}
        </fieldset>
        <ProductImagesField
          label="Ảnh / video minh chứng"
          max={EVIDENCE_MAX}
          showCover={false}
          allowVideo
          value={evidence}
          onChange={(updater) =>
            setEvidence((current) => (typeof updater === 'function' ? updater(current) : updater))
          }
          onUploaded={(urls) => urls.forEach((url) => uploaded.current.add(url))}
          upload={uploadReturnEvidence}
          disabled={saving}
        />
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
            {saving ? 'Đang gửi…' : 'Gửi yêu cầu'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
