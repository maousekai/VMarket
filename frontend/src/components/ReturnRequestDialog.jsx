import { useEffect, useRef, useState } from 'react'
import Modal from './Modal'
import ProductImagesField from './ProductImagesField'
import { useProductLookup, useShopLookup } from '../hooks/useCatalogLookup'
import { getApiError } from '../services/api/apiClient'
import { discardUploaded } from '../services/api/mediaApi'
import { createReturn, deleteReturnEvidence, uploadReturnEvidence } from '../services/api/orderApi'
import { formatMoney } from '../utils/formatMoney'

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
 * Yeu cau tra hang cho cac dong hang con tra duoc. Order Service chi nhan cac dong cua CUNG
 * mot gian hang trong mot yeu cau, nen chon dong cua shop khac se bo chon cac dong truoc do.
 */
export default function ReturnRequestDialog({ order, items, onClose, onCreated }) {
  const products = useProductLookup(items.map((item) => item.productId))
  const shops = useShopLookup(items.map((item) => item.shopId))
  const [picked, setPicked] = useState({})
  const [reason, setReason] = useState(RETURN_REASONS[0])
  const [otherReason, setOtherReason] = useState('')
  const [evidence, setEvidence] = useState([])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const uploaded = useRef(new Set())
  const submitted = useRef(false)

  useEffect(
    () => () => {
      if (!submitted.current) discardUploaded([...uploaded.current], deleteReturnEvidence)
    },
    [],
  )

  const pickedIds = Object.keys(picked)
  const pickedShop = items.find((item) => picked[item.id])?.shopId

  function toggle(item, on) {
    setPicked((current) => {
      const sameShop = Object.fromEntries(
        Object.entries(current).filter(
          ([id]) => items.find((i) => i.id === id)?.shopId === item.shopId,
        ),
      )
      if (!on) {
        delete sameShop[item.id]
        return sameShop
      }
      return { ...sameShop, [item.id]: item.quantity }
    })
  }

  async function submit(event) {
    event.preventDefault()
    const finalReason = reason === OTHER ? otherReason.trim() : reason
    if (!pickedIds.length) return setError('Chọn ít nhất một sản phẩm cần trả.')
    if (!finalReason) return setError('Vui lòng mô tả lý do trả hàng.')
    if (!evidence.length) return setError('Vui lòng thêm ít nhất 1 ảnh minh chứng.')
    setSaving(true)
    setError('')
    try {
      const created = await createReturn(order.id, {
        reason: finalReason,
        evidenceUrls: evidence,
        items: items
          .filter((item) => picked[item.id])
          .map((item) => ({
            orderItemId: item.id,
            productId: item.productId,
            variantId: item.variantId || null,
            quantity: picked[item.id],
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
      description="Mỗi yêu cầu gồm sản phẩm của một gian hàng. Gian hàng phản hồi trong 48 giờ."
      onClose={onClose}
    >
      <form className="return-form" noValidate onSubmit={submit}>
        <fieldset>
          <legend>Sản phẩm cần trả</legend>
          <ul className="return-form__items">
            {items.map((item) => {
              const product = products[item.productId]?.value
              const variant = product?.variants?.find((entry) => entry.id === item.variantId)
              const otherShop = pickedShop && pickedShop !== item.shopId
              return (
                <li key={item.id}>
                  <label>
                    <input
                      type="checkbox"
                      checked={Boolean(picked[item.id])}
                      onChange={(event) => toggle(item, event.target.checked)}
                    />
                    <span>
                      <strong>{product?.name || 'Sản phẩm'}</strong>
                      <small>
                        {[
                          shops[item.shopId]?.value?.name,
                          variant && Object.values(variant.attributes || {}).join(' · '),
                          formatMoney(item.unitPrice),
                        ]
                          .filter(Boolean)
                          .join(' · ')}
                        {otherShop && ' · khác gian hàng đang chọn'}
                      </small>
                    </span>
                  </label>
                  {picked[item.id] && item.quantity > 1 && (
                    <label className="return-form__qty">
                      <span>Số lượng trả</span>
                      <select
                        value={picked[item.id]}
                        onChange={(event) =>
                          setPicked({ ...picked, [item.id]: Number(event.target.value) })
                        }
                      >
                        {Array.from({ length: item.quantity }, (_, index) => (
                          <option key={index + 1} value={index + 1}>
                            {index + 1}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                </li>
              )
            })}
          </ul>
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
          label="Ảnh minh chứng"
          max={EVIDENCE_MAX}
          showCover={false}
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
