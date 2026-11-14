import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AddressFormDialog from '../components/AddressFormDialog'
import { useCart } from '../cart/useCart'
import { lineKey } from '../cart/cartMath'
import { useProductLookup, useShopLookup } from '../hooks/useCatalogLookup'
import { getApiError } from '../services/api/apiClient'
import { validateCart } from '../services/api/cartApi'
import { checkoutOrders, PAYMENT_METHOD } from '../services/api/orderApi'
import { startVnpayQueue } from '../order/vnpay'
import { clearCheckoutLines, lineToItem, loadCheckoutLines } from '../order/checkoutSelection'
import { listMyAddresses, newIdempotencyKey } from '../services/api/userApi'
import { formatMoney } from '../utils/formatMoney'
import { PAYMENT_METHOD_LABELS } from '../utils/order'
import { imageUrlOrEmpty } from '../utils/shop'
import { describeVariant } from '../utils/variant'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/cart.css'
import '../components/checkout.css'

const NOTE_MAX = 255

function AddressPicker({ state, selected, onSelect, onAdd }) {
  if (state.status === 'loading')
    return (
      <p className="account-notice" role="status">
        Đang tải sổ địa chỉ…
      </p>
    )
  if (state.status === 'error')
    return (
      <p className="form-message form-message--error" role="alert">
        Không tải được sổ địa chỉ. {state.error}
      </p>
    )
  if (!state.addresses.length)
    return (
      <div className="checkout-empty-address">
        <p>Bạn chưa có địa chỉ nhận hàng.</p>
        <button className="account-button" type="button" onClick={onAdd}>
          Thêm địa chỉ
        </button>
      </div>
    )
  return (
    <div className="checkout-addresses" role="radiogroup" aria-label="Địa chỉ nhận hàng">
      {state.addresses.map((address) => (
        <label
          key={address.id}
          className={`checkout-address${selected === address.id ? ' is-selected' : ''}`}
        >
          <input
            type="radio"
            name="address"
            value={address.id}
            checked={selected === address.id}
            onChange={() => onSelect(address.id)}
          />
          <span>
            <strong>
              {address.recipientName} · {address.phone}
            </strong>
            {address.isDefault && <em className="checkout-address__default">Mặc định</em>}
            <small>
              {[address.streetAddress, address.ward, address.district, address.province]
                .filter(Boolean)
                .join(', ')}
            </small>
          </span>
        </label>
      ))}
    </div>
  )
}

export default function CheckoutPage() {
  const cart = useCart()
  const navigate = useNavigate()
  const location = useLocation()
  const [lines] = useState(() => location.state?.lines || loadCheckoutLines())
  const [addingAddress, setAddingAddress] = useState(false)
  const [addressReload, setAddressReload] = useState(0)
  const [addresses, setAddresses] = useState({ status: 'loading', addresses: [], error: '' })
  const [addressId, setAddressId] = useState('')
  const [note, setNote] = useState('')
  const [method, setMethod] = useState(PAYMENT_METHOD.cod)
  const [submitting, setSubmitting] = useState('')
  const [error, setError] = useState('')
  const idempotency = useRef({ key: newIdempotencyKey(), reuse: false })

  // Chi nhung dong da chon o gio hang moi di qua thanh toan.
  const groups = (cart.cart?.groups || [])
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => lines.includes(lineKey(item))),
    }))
    .filter((group) => group.items.length > 0)
  const items = groups.flatMap((group) => group.items)
  const totalAmount = items.reduce((sum, item) => sum + Number(item.lineTotal), 0)
  const totalQuantity = items.reduce((sum, item) => sum + item.quantity, 0)
  const products = useProductLookup(items.map((item) => item.productId))
  const shops = useShopLookup(groups.map((group) => group.shopId))

  useEffect(() => {
    let current = true
    listMyAddresses()
      .then((list) => {
        if (!current) return
        setAddresses({ status: 'ready', addresses: list, error: '' })
        setAddressId(
          (selected) =>
            (list.some((address) => address.id === selected) && selected) ||
            (list.find((address) => address.isDefault) || list[0])?.id ||
            '',
        )
      })
      .catch((failure) => {
        if (current)
          setAddresses({ status: 'error', addresses: [], error: getApiError(failure).message })
      })
    return () => {
      current = false
    }
  }, [addressReload])

  async function submit(event) {
    event.preventDefault()
    if (submitting) return
    if (!addressId) return setError('Vui lòng chọn địa chỉ nhận hàng.')
    setError('')
    setSubmitting('validate')
    try {
      await cart.flush()
      const check = await validateCart()
      const blocking = (check.issues || []).some(
        (issue) => !issue.productId || lines.includes(lineKey(issue)),
      )
      if (blocking) {
        navigate('/cart', {
          replace: true,
          state: { message: 'Giỏ hàng vừa thay đổi giá hoặc tồn kho, vui lòng kiểm tra lại.' },
        })
        return
      }
      if (!idempotency.current.reuse) idempotency.current.key = newIdempotencyKey()
      setSubmitting('order')
      const orders = await checkoutOrders(
        { addressId, note, paymentMethod: method, items: lines.map(lineToItem) },
        idempotency.current.key,
      )
      clearCheckoutLines()
      cart.refresh().catch(() => {})
      const ids = orders.map((order) => order.id)
      if (method === PAYMENT_METHOD.vnpay) {
        // Moi gian hang mot don: thanh toan lan luot tung don tren VNPay, sau moi don co nut sang don tiep theo.
        startVnpayQueue(ids, navigate)
        return
      }
      navigate(`/checkout/success?orders=${ids.map(encodeURIComponent).join(',')}`, {
        replace: true,
      })
    } catch (failure) {
      const apiError = getApiError(failure)
      idempotency.current.reuse = apiError.retryable
      setError(apiError.message)
      setSubmitting('')
    }
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
  if (!items.length) {
    return (
      <main className="cart-page">
        <div className="account-card cart-empty">
          <h1>Chưa chọn sản phẩm để thanh toán</h1>
          <p>Hãy chọn sản phẩm trong giỏ hàng rồi bấm “Tiến hành đặt hàng”.</p>
          <Link className="button button--primary" to="/cart">
            Về giỏ hàng
          </Link>
        </div>
      </main>
    )
  }

  return (
    <main className="cart-page checkout-page">
      <ol className="checkout-steps" aria-label="Các bước đặt hàng">
        <li className="is-done">Giỏ hàng</li>
        <li className="is-current" aria-current="step">
          Thanh toán
        </li>
        <li>Hoàn tất</li>
      </ol>
      <form className="cart-layout" noValidate onSubmit={submit}>
        <div className="cart-groups">
          <section className="account-card checkout-section" aria-labelledby="checkout-address">
            <div className="checkout-section__head">
              <h2 id="checkout-address">Địa chỉ nhận hàng</h2>
              <button type="button" className="link-button" onClick={() => setAddingAddress(true)}>
                + Thêm địa chỉ mới
              </button>
            </div>
            <AddressPicker
              state={addresses}
              selected={addressId}
              onSelect={setAddressId}
              onAdd={() => setAddingAddress(true)}
            />
          </section>

          <section className="account-card checkout-section" aria-labelledby="checkout-items">
            <h2 id="checkout-items">Kiểm tra sản phẩm</h2>
            {groups.length > 1 && (
              <p className="cart-split-note">
                Đơn sẽ được tách thành {groups.length} đơn theo gian hàng; mỗi đơn có tiến độ giao,
                huỷ và trả hàng riêng.
              </p>
            )}
            {groups.map((group) => (
              <div className="checkout-shop" key={group.shopId}>
                <h3>{shops[group.shopId]?.value?.name || 'Gian hàng'}</h3>
                <ul>
                  {group.items.map((item) => {
                    const product = products[item.productId]?.value
                    const variant = product?.variants?.find((entry) => entry.id === item.variantId)
                    const image = imageUrlOrEmpty(product?.imageUrls?.[0])
                    return (
                      <li key={lineKey(item)} className="checkout-item">
                        <span className="cart-line__media">
                          {image && <img src={image} alt="" />}
                        </span>
                        <span className="checkout-item__info">
                          <strong>{product?.name || 'Sản phẩm'}</strong>
                          {variant && <small>{describeVariant(variant)}</small>}
                        </span>
                        <span className="checkout-item__qty">x{item.quantity}</span>
                        <span className="checkout-item__total">{formatMoney(item.lineTotal)}</span>
                      </li>
                    )
                  })}
                </ul>
              </div>
            ))}
            <label className="checkout-note">
              <span>Ghi chú cho đơn hàng (không bắt buộc)</span>
              <textarea
                rows={2}
                maxLength={NOTE_MAX}
                placeholder="Ví dụ: gọi trước khi giao"
                value={note}
                onChange={(event) => setNote(event.target.value)}
              />
            </label>
          </section>

          <section className="account-card checkout-section" aria-labelledby="checkout-payment">
            <h2 id="checkout-payment">Phương thức thanh toán</h2>
            <div className="checkout-methods" role="radiogroup" aria-labelledby="checkout-payment">
              {[PAYMENT_METHOD.cod, PAYMENT_METHOD.vnpay].map((value) => (
                <label
                  key={value}
                  className={`checkout-method${method === value ? ' is-selected' : ''}`}
                >
                  <input
                    type="radio"
                    name="payment"
                    value={value}
                    checked={method === value}
                    onChange={() => setMethod(value)}
                  />
                  <span>
                    <strong>{PAYMENT_METHOD_LABELS[value]}</strong>
                    <small>
                      {value === PAYMENT_METHOD.cod
                        ? 'Trả tiền mặt cho shipper khi nhận hàng.'
                        : 'Chuyển sang cổng VNPay để thanh toán, đơn được giữ hàng trong thời gian thanh toán.'}
                    </small>
                  </span>
                </label>
              ))}
            </div>
          </section>
        </div>

        <aside className="account-card cart-summary" aria-label="Tóm tắt thanh toán">
          <h2>Tóm tắt thanh toán</h2>
          <dl>
            <div>
              <dt>Tạm tính ({totalQuantity} sản phẩm)</dt>
              <dd>{formatMoney(totalAmount)}</dd>
            </div>
            <div>
              <dt>Phí giao hàng</dt>
              <dd>Tính khi giao</dd>
            </div>
          </dl>
          <div className="cart-summary__total">
            <span>Tổng thanh toán</span>
            <strong>{formatMoney(totalAmount)}</strong>
          </div>
          {error && (
            <p className="form-message form-message--error" role="alert">
              {error}
            </p>
          )}
          <button
            type="submit"
            className="button button--primary cart-summary__cta"
            disabled={Boolean(submitting) || !addressId}
          >
            {submitting === 'validate'
              ? 'Đang kiểm tra giỏ hàng…'
              : submitting === 'order'
                ? 'Đang đặt hàng…'
                : groups.length > 1
                  ? `Đặt ${groups.length} đơn hàng`
                  : 'Đặt hàng'}
          </button>
          <Link className="checkout-back" to="/cart">
            ‹ Quay lại giỏ hàng
          </Link>
        </aside>
      </form>
      {addingAddress && (
        <AddressFormDialog
          isFirst={addresses.addresses.length === 0}
          onCancel={() => setAddingAddress(false)}
          onSaved={(_message, _warning, saved) => {
            setAddingAddress(false)
            if (saved?.id) setAddressId(saved.id)
            setAddressReload((value) => value + 1)
          }}
        />
      )}
    </main>
  )
}
