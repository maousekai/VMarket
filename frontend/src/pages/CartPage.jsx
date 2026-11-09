import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import Modal from '../components/Modal'
import { lineKey } from '../cart/cartMath'
import { useCart } from '../cart/useCart'
import { useProductLookup, useShopLookup } from '../hooks/useCatalogLookup'
import { getApiError } from '../services/api/apiClient'
import { CART_LIMITS, validateCart } from '../services/api/cartApi'
import { formatMoney } from '../utils/formatMoney'
import { imageUrlOrEmpty } from '../utils/shop'
import { describeVariant } from '../utils/variant'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/cart.css'

const ITEM_ISSUES = new Set(['NOT_FOUND', 'PRICE_CHANGED', 'OUT_OF_STOCK', 'SHOP_CHANGED'])

function issueText(issue) {
  switch (issue.type) {
    case 'PRICE_CHANGED':
      return `Giá đã đổi từ ${formatMoney(issue.cartPrice)} thành ${formatMoney(issue.currentPrice)}.`
    case 'OUT_OF_STOCK':
      return issue.availableStock > 0
        ? `Chỉ còn ${issue.availableStock} sản phẩm.`
        : 'Sản phẩm đã hết hàng.'
    case 'SHOP_CHANGED':
      return 'Sản phẩm đã chuyển sang gian hàng khác, hãy xoá và thêm lại.'
    default:
      return 'Sản phẩm không còn được bán.'
  }
}

function CartLine({ item, product, issue, selected, onSelect, onFix, busy }) {
  const cart = useCart()
  const [error, setError] = useState('')
  const variant = product?.variants?.find((entry) => entry.id === item.variantId)
  const image = imageUrlOrEmpty(product?.imageUrls?.[0])
  const unavailable = product === null
  const name = product?.name || (unavailable ? 'Sản phẩm không còn bán' : 'Đang tải…')
  const max = Math.min(CART_LIMITS.maxQuantity, variant?.availableStock ?? CART_LIMITS.maxQuantity)

  const change = (quantity) => {
    setError('')
    cart.setQuantity(item, quantity, { onError: (apiError) => setError(apiError.message) })
  }

  return (
    <li className={`cart-line${issue ? ' has-issue' : ''}`}>
      <input
        type="checkbox"
        aria-label={`Chọn ${name}`}
        checked={selected}
        onChange={(event) => onSelect(event.target.checked)}
      />
      <div className="cart-line__media">
        {image ? <img src={image} alt="" /> : <span aria-hidden="true" />}
      </div>
      <div className="cart-line__info">
        {product ? (
          <Link to={`/products/${encodeURIComponent(item.productId)}`}>{name}</Link>
        ) : (
          <strong>{name}</strong>
        )}
        {variant && <small>{describeVariant(variant)}</small>}
        {issue && (
          <div className="cart-line__issue" role="alert">
            <span>{issueText(issue)}</span>
            {issue.type === 'PRICE_CHANGED' && (
              <button type="button" className="link-button" disabled={busy} onClick={onFix}>
                Cập nhật giá mới
              </button>
            )}
            {issue.type === 'OUT_OF_STOCK' && issue.availableStock > 0 && (
              <button type="button" className="link-button" disabled={busy} onClick={onFix}>
                Giảm còn {issue.availableStock}
              </button>
            )}
          </div>
        )}
        {error && (
          <p className="field-error" role="alert">
            {error}
          </p>
        )}
      </div>
      <div className="cart-line__price">{formatMoney(item.unitPrice)}</div>
      <div className="quantity-stepper" role="group" aria-label={`Số lượng ${name}`}>
        <button
          type="button"
          aria-label="Giảm"
          disabled={item.quantity <= 1}
          onClick={() => change(item.quantity - 1)}
        >
          −
        </button>
        <input
          aria-label="Số lượng"
          inputMode="numeric"
          value={item.quantity}
          onChange={(event) => {
            const next = Number.parseInt(event.target.value.replace(/\D/g, ''), 10)
            if (Number.isFinite(next) && next > 0) change(Math.min(next, CART_LIMITS.maxQuantity))
          }}
        />
        <button
          type="button"
          aria-label="Tăng"
          disabled={item.quantity >= max}
          onClick={() => change(item.quantity + 1)}
        >
          +
        </button>
      </div>
      <div className="cart-line__total">{formatMoney(item.lineTotal)}</div>
      <button
        type="button"
        className="cart-line__remove"
        aria-label={`Xoá ${name} khỏi giỏ`}
        onClick={() =>
          cart.removeItem(item).catch((failure) => setError(getApiError(failure).message))
        }
      >
        ×
      </button>
    </li>
  )
}

export default function CartPage() {
  const cart = useCart()
  const navigate = useNavigate()
  const location = useLocation()
  const [selected, setSelected] = useState(() => new Set())
  const [validation, setValidation] = useState(null)
  const [checking, setChecking] = useState(false)
  const [pageError, setPageError] = useState('')
  const [fixing, setFixing] = useState('')
  const [confirmRemove, setConfirmRemove] = useState(false)

  const groups = cart.cart?.groups || []
  const items = groups.flatMap((group) => group.items)
  const products = useProductLookup(items.map((item) => item.productId))
  const shops = useShopLookup(groups.map((group) => group.shopId))
  const issues = new Map(
    (validation?.issues || [])
      .filter((issue) => ITEM_ISSUES.has(issue.type))
      .map((issue) => [lineKey(issue), issue]),
  )
  const generalIssue = validation?.issues?.find((issue) => !ITEM_ISSUES.has(issue.type))
  const selectedItems = items.filter((item) => selected.has(lineKey(item)))
  const allSelected = items.length > 0 && selectedItems.length === items.length

  const toggle = (keys, on) =>
    setSelected((current) => {
      const next = new Set(current)
      keys.forEach((key) => (on ? next.add(key) : next.delete(key)))
      return next
    })

  async function checkout() {
    setChecking(true)
    setPageError('')
    try {
      await cart.flush()
      const result = await validateCart()
      setValidation(result)
      if (result.ok) navigate('/checkout')
    } catch (failure) {
      setPageError(getApiError(failure).message)
    } finally {
      setChecking(false)
    }
  }

  async function fix(item, issue) {
    setFixing(lineKey(item))
    setPageError('')
    try {
      if (issue.type === 'PRICE_CHANGED') {
        await cart.removeItem(item)
        await cart.addItem({ ...item, unitPrice: issue.currentPrice })
      } else if (issue.type === 'OUT_OF_STOCK') {
        cart.setQuantity(item, issue.availableStock)
        await cart.flush()
      }
      setValidation((current) => ({
        ...current,
        issues: current.issues.filter((entry) => lineKey(entry) !== lineKey(item)),
      }))
    } catch (failure) {
      setPageError(getApiError(failure).message)
    } finally {
      setFixing('')
    }
  }

  async function removeSelected() {
    setConfirmRemove(false)
    setPageError('')
    const results = await Promise.allSettled(selectedItems.map((item) => cart.removeItem(item)))
    const failed = results.find((result) => result.status === 'rejected')
    if (failed) setPageError(getApiError(failed.reason).message)
    setSelected(new Set())
  }

  if (cart.status === 'loading' || cart.status === 'idle') {
    return (
      <main className="cart-page">
        <p className="account-notice" role="status">
          Đang tải giỏ hàng…
        </p>
      </main>
    )
  }
  if (cart.status === 'error' && !cart.cart) {
    return (
      <main className="cart-page">
        <div className="account-card account-state">
          <p role="alert">Không tải được giỏ hàng. {getApiError(cart.error).message}</p>
          <button className="account-button" type="button" onClick={() => cart.refresh()}>
            Thử lại
          </button>
        </div>
      </main>
    )
  }
  if (!items.length) {
    return (
      <main className="cart-page">
        <h1 className="cart-page__title">Giỏ hàng</h1>
        <div className="account-card cart-empty">
          <h2>Giỏ hàng đang trống</h2>
          <p>Khám phá sản phẩm của các gian hàng tại Đà Nẵng và thêm vào giỏ nhé.</p>
          <Link className="button button--primary" to="/">
            Tiếp tục mua sắm
          </Link>
        </div>
      </main>
    )
  }

  return (
    <main className="cart-page">
      <div className="cart-page__head">
        <h1 className="cart-page__title">
          Giỏ hàng <small>({cart.totalQuantity} sản phẩm)</small>
        </h1>
        <Link to="/">‹ Tiếp tục mua sắm</Link>
      </div>
      {location.state?.message && !validation && (
        <p className="account-notice account-notice--warning" role="status">
          {location.state.message}
        </p>
      )}
      {pageError && (
        <p className="account-notice account-notice--warning" role="alert">
          {pageError}
        </p>
      )}
      {generalIssue && (
        <p className="account-notice account-notice--warning" role="alert">
          {generalIssue.message}
        </p>
      )}
      {issues.size > 0 && (
        <p className="account-notice account-notice--warning" role="alert">
          Một số sản phẩm đã thay đổi giá hoặc tồn kho. Vui lòng xử lý trước khi đặt hàng.
        </p>
      )}
      <div className="cart-layout">
        <div className="cart-groups">
          <div className="account-card cart-toolbar">
            <label>
              <input
                type="checkbox"
                checked={allSelected}
                onChange={(event) => toggle(items.map(lineKey), event.target.checked)}
              />
              Chọn tất cả
            </label>
            <button
              type="button"
              className="link-button cart-toolbar__remove"
              disabled={!selectedItems.length}
              onClick={() => setConfirmRemove(true)}
            >
              Xoá đã chọn ({selectedItems.length})
            </button>
          </div>
          {groups.map((group) => {
            const shop = shops[group.shopId]?.value
            const keys = group.items.map(lineKey)
            return (
              <section
                className="account-card cart-group"
                key={group.shopId}
                aria-label={shop?.name || 'Gian hàng'}
              >
                <header className="cart-group__head">
                  <input
                    type="checkbox"
                    aria-label={`Chọn tất cả sản phẩm của ${shop?.name || 'gian hàng'}`}
                    checked={keys.every((key) => selected.has(key))}
                    onChange={(event) => toggle(keys, event.target.checked)}
                  />
                  <Link to={`/shops/${encodeURIComponent(group.shopId)}`}>
                    {shop?.name || 'Gian hàng'}
                  </Link>
                  {shop?.province && <span className="cart-group__area">{shop.province}</span>}
                </header>
                <ul className="cart-lines">
                  {group.items.map((item) => {
                    const key = lineKey(item)
                    const entry = products[item.productId]
                    const issue = issues.get(key)
                    return (
                      <CartLine
                        key={key}
                        item={item}
                        product={entry ? entry.value : undefined}
                        issue={issue}
                        selected={selected.has(key)}
                        busy={fixing === key}
                        onSelect={(on) => toggle([key], on)}
                        onFix={() => fix(item, issue)}
                      />
                    )
                  })}
                </ul>
                <footer className="cart-group__foot">
                  Tạm tính gian hàng: <strong>{formatMoney(group.subtotal)}</strong>
                </footer>
              </section>
            )
          })}
          {groups.length > 1 && (
            <p className="cart-split-note">
              Giỏ hàng gồm {groups.length} gian hàng; mỗi gian hàng xác nhận và giao phần hàng của
              mình.
            </p>
          )}
        </div>
        <aside className="account-card cart-summary" aria-label="Tóm tắt đơn hàng">
          <h2>Tóm tắt đơn hàng</h2>
          <dl>
            <div>
              <dt>Tạm tính ({cart.totalQuantity} sản phẩm)</dt>
              <dd>{formatMoney(cart.cart.totalAmount)}</dd>
            </div>
            <div>
              <dt>Phí vận chuyển</dt>
              <dd>Tính khi giao</dd>
            </div>
          </dl>
          <div className="cart-summary__total">
            <span>Tổng thanh toán</span>
            <strong>{formatMoney(cart.cart.totalAmount)}</strong>
          </div>
          <button
            type="button"
            className="button button--primary cart-summary__cta"
            disabled={checking || items.length === 0}
            onClick={checkout}
          >
            {checking ? 'Đang kiểm tra giá và tồn kho…' : 'Tiến hành đặt hàng'}
          </button>
          <p className="cart-summary__note">
            Giá và tồn kho được kiểm tra lại trước khi đặt hàng. Thanh toán khi nhận hàng (COD) hoặc
            PayOS.
          </p>
        </aside>
      </div>

      {confirmRemove && (
        <Modal
          title="Xoá sản phẩm đã chọn?"
          description={`${selectedItems.length} sản phẩm sẽ bị xoá khỏi giỏ hàng.`}
          onClose={() => setConfirmRemove(false)}
        >
          <div className="account-actions">
            <button
              type="button"
              className="account-button account-button--secondary"
              data-autofocus
              onClick={() => setConfirmRemove(false)}
            >
              Giữ lại
            </button>
            <button
              type="button"
              className="account-button account-button--danger"
              onClick={removeSelected}
            >
              Xoá
            </button>
          </div>
        </Modal>
      )}
    </main>
  )
}
