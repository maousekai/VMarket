import { Link } from 'react-router-dom'
import { useProductLookup } from '../hooks/useCatalogLookup'
import { formatMoney } from '../utils/formatMoney'
import {
  ORDER_STATUS_LABELS,
  ORDER_STEPS,
  orderStepIndex,
  RETURN_STATUS_LABELS,
} from '../utils/order'
import { imageUrlOrEmpty } from '../utils/shop'
import { describeVariant } from '../utils/variant'
import './orders.css'

export function OrderStatusBadge({ status }) {
  return (
    <span className={`order-badge order-badge--${String(status).toLowerCase()}`}>
      {ORDER_STATUS_LABELS[status] || status}
    </span>
  )
}

export function ReturnStatusBadge({ status }) {
  return (
    <span className={`order-badge order-badge--return-${String(status).toLowerCase()}`}>
      {RETURN_STATUS_LABELS[status] || status}
    </span>
  )
}

/** Timeline theo state machine cua Order Service; don huy hien mot dong rieng. */
export function OrderTimeline({ status }) {
  const current = orderStepIndex(status)
  if (current < 0) {
    return (
      <p className="order-timeline__cancelled" role="status">
        Đơn hàng đã bị huỷ.
      </p>
    )
  }
  return (
    <ol className="order-timeline" aria-label="Tiến trình đơn hàng">
      {ORDER_STEPS.map((step, index) => (
        <li
          key={step.key}
          className={index < current ? 'is-done' : index === current ? 'is-current' : ''}
          aria-current={index === current ? 'step' : undefined}
        >
          <span className="order-timeline__dot" aria-hidden="true" />
          <span>{index === 0 && status === 'WAITING_PAYMENT' ? 'Chờ thanh toán' : step.label}</span>
        </li>
      ))}
    </ol>
  )
}

/**
 * Dong hang cua don (chi co id + gia snapshot); ten / anh / phan loai doc tu catalog.
 * `limit` rut gon danh sach tren the don.
 */
export function OrderItemsList({ items, limit, renderExtra }) {
  const products = useProductLookup(items.map((item) => item.productId))
  const shown = limit ? items.slice(0, limit) : items
  return (
    <>
      <ul className="order-items">
        {shown.map((item, index) => {
          const entry = products[item.productId]
          const product = entry?.value
          const variant = product?.variants?.find((candidate) => candidate.id === item.variantId)
          const image = imageUrlOrEmpty(product?.imageUrls?.[0])
          return (
            <li
              key={item.id || `${item.productId}-${item.variantId}-${index}`}
              className="order-item"
            >
              <span className="order-item__media">{image && <img src={image} alt="" />}</span>
              <span className="order-item__info">
                {product ? (
                  <Link to={`/products/${encodeURIComponent(item.productId)}`}>{product.name}</Link>
                ) : (
                  <strong>{entry ? 'Sản phẩm không còn bán' : 'Đang tải…'}</strong>
                )}
                {variant && <small>{describeVariant(variant)}</small>}
                <small>Số lượng: {item.quantity}</small>
              </span>
              <span className="order-item__price">
                {formatMoney(item.unitPrice)}
                {item.quantity > 1 && <small>Thành tiền {formatMoney(item.lineTotal)}</small>}
              </span>
              {renderExtra?.(item)}
            </li>
          )
        })}
      </ul>
      {limit && items.length > limit && (
        <p className="order-items__more">+ {items.length - limit} sản phẩm khác</p>
      )}
    </>
  )
}
