import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { useCart } from '../cart/useCart'
import { getApiError } from '../services/api/apiClient'

const messages = {
  CART_ITEM_LIMIT_EXCEEDED: 'Giỏ hàng đã đủ 50 sản phẩm. Hãy thanh toán hoặc xoá bớt.',
  CART_QUANTITY_LIMIT_EXCEEDED: 'Mỗi sản phẩm chỉ được tối đa 999 trong giỏ.',
}

/** "Thêm vào giỏ" / "Mua ngay" cho trang chi tiết; khách chưa đăng nhập được đưa tới /login. */
export default function AddToCartActions({ product, variant, quantity, soldOut }) {
  const { session } = useAuth()
  const cart = useCart()
  const navigate = useNavigate()
  const location = useLocation()
  const [busy, setBusy] = useState('')
  const [result, setResult] = useState(null)
  const disabled = !variant || soldOut || Boolean(busy)

  async function add(buyNow) {
    if (!session) {
      navigate('/login', {
        state: { returnTo: `${location.pathname}${location.search}` },
      })
      return
    }
    setBusy(buyNow ? 'buy' : 'add')
    setResult(null)
    try {
      await cart.addItem({
        productId: product.id,
        variantId: variant.id,
        shopId: product.shopId,
        quantity,
        unitPrice: variant.price,
      })
      if (buyNow) {
        navigate('/cart')
        return
      }
      setResult({ ok: true })
    } catch (failure) {
      const apiError = getApiError(failure)
      setResult({ ok: false, message: messages[apiError.code] || apiError.message })
    } finally {
      setBusy('')
    }
  }

  return (
    <div className="pdp-actions">
      <button
        type="button"
        className="button button--secondary"
        disabled={disabled}
        onClick={() => add(false)}
      >
        {busy === 'add' ? 'Đang thêm…' : 'Thêm vào giỏ hàng'}
      </button>
      <button
        type="button"
        className="button button--primary"
        disabled={disabled}
        onClick={() => add(true)}
      >
        {busy === 'buy' ? 'Đang xử lý…' : 'Mua ngay'}
      </button>
      {!variant && (
        <p className="form-message" role="status">
          Chọn đủ phân loại để thêm vào giỏ.
        </p>
      )}
      {result?.ok && (
        <p className="form-message form-message--success" role="status">
          Đã thêm {quantity} sản phẩm vào giỏ. <Link to="/cart">Xem giỏ hàng</Link>
        </p>
      )}
      {result && !result.ok && (
        <p className="form-message form-message--error" role="alert">
          {result.message}
        </p>
      )}
    </div>
  )
}
