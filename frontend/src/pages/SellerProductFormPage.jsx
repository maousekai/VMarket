import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import ProductForm from '../components/ProductForm'
import { getApiError } from '../services/api/apiClient'
import { deleteProductMedia, discardUploaded } from '../services/api/mediaApi'
import {
  createProduct,
  listCategories,
  listMySellerProducts,
  PRODUCT_STATUS,
  updateProduct,
} from '../services/api/productApi'
import { getMyShop } from '../services/api/shopApi'
import { newIdempotencyKey } from '../services/api/userApi'
import { sortTree } from '../utils/category'
import {
  emptyProductForm,
  formToRequest,
  productToForm,
  validateProductForm,
} from '../utils/productForm'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/seller.css'
import '../components/sellerProducts.css'

const serverMessages = {
  FORBIDDEN: 'Gian hàng cần được duyệt trước khi đăng sản phẩm.',
  STOCK_BELOW_RESERVED: 'Tồn kho mới thấp hơn số lượng đang được giữ cho đơn hàng.',
  VARIANT_RESERVED: 'Không thể xoá phân loại đang được giữ cho đơn hàng.',
}

export default function SellerProductFormPage() {
  const { productId } = useParams()
  const editing = Boolean(productId)
  const navigate = useNavigate()
  const [load, setLoad] = useState({ status: 'loading', error: '' })
  const [attempt, setAttempt] = useState(0)
  const [context, setContext] = useState({ shopId: null, categories: [], product: null })
  const [form, setForm] = useState(emptyProductForm)
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState('')
  const [submitError, setSubmitError] = useState('')
  const uploaded = useRef(new Set())
  const saved = useRef(false)
  const idempotency = useRef({ key: newIdempotencyKey(), reuse: false })

  useEffect(() => {
    let current = true
    setLoad({ status: 'loading', error: '' })
    Promise.all([getMyShop(), listCategories(), editing ? listMySellerProducts() : null])
      .then(([shop, categories, products]) => {
        if (!current) return
        const product = editing ? products?.find((item) => item.id === productId) : null
        if (editing && !product) {
          setLoad({ status: 'error', error: 'Không tìm thấy sản phẩm trong gian hàng của bạn.' })
          return
        }
        setContext({ shopId: shop.id, categories: sortTree(categories), product })
        setForm(product ? productToForm(product) : emptyProductForm())
        setLoad({ status: 'ready', error: '' })
      })
      .catch((failure) => {
        if (!current) return
        const apiError = getApiError(failure)
        setLoad({ status: 'error', error: serverMessages[apiError.code] || apiError.message })
      })
    return () => {
      current = false
    }
  }, [editing, productId, attempt])

  // Images uploaded in this session but never saved are removed from storage.
  useEffect(
    () => () => {
      if (!saved.current) discardUploaded([...uploaded.current], deleteProductMedia)
    },
    [],
  )

  async function submit(status) {
    if (saving) return
    const nextErrors = validateProductForm(form)
    setErrors(nextErrors)
    setSubmitError('')
    if (Object.keys(nextErrors).length) {
      setSubmitError('Vui lòng kiểm tra lại các trường được đánh dấu.')
      return
    }
    if (!idempotency.current.reuse) idempotency.current.key = newIdempotencyKey()
    setSaving(status)
    try {
      const body = formToRequest(form, { shopId: context.shopId, status })
      const result = editing
        ? await updateProduct(productId, body, idempotency.current.key)
        : await createProduct(body, idempotency.current.key)
      saved.current = true
      const kept = new Set(result.imageUrls || body.imageUrls)
      const original = context.product?.imageUrls || []
      discardUploaded(
        [...new Set([...uploaded.current, ...original])].filter((url) => !kept.has(url)),
        deleteProductMedia,
      )
      navigate('/seller/products', {
        replace: true,
        state: {
          notice:
            status === PRODUCT_STATUS.draft
              ? `Đã lưu nháp “${result.name}”.`
              : `Đã ${editing ? 'cập nhật' : 'đăng bán'} “${result.name}”.`,
        },
      })
    } catch (failure) {
      const apiError = getApiError(failure)
      idempotency.current.reuse = apiError.retryable
      setSubmitError(serverMessages[apiError.code] || apiError.message)
      setSaving('')
    }
  }

  if (load.status === 'loading') {
    return (
      <section className="seller-page">
        <p className="account-notice" role="status">
          Đang tải…
        </p>
      </section>
    )
  }
  if (load.status === 'error') {
    return (
      <section className="seller-page">
        <div className="account-card account-state">
          <p role="alert">{load.error}</p>
          <div className="account-actions">
            <button
              className="account-button"
              type="button"
              onClick={() => setAttempt((value) => value + 1)}
            >
              Thử lại
            </button>
            <Link className="account-button account-button--secondary" to="/seller/products">
              Về danh sách
            </Link>
          </div>
        </div>
      </section>
    )
  }

  const product = context.product
  return (
    <section className="seller-page seller-product-editor" aria-labelledby="product-form-title">
      <div className="seller-page__header seller-page__header--row">
        <div>
          <Link className="link-button" to="/seller/products">
            ‹ Quản lý sản phẩm
          </Link>
          <h1 id="product-form-title">{editing ? 'Chỉnh sửa sản phẩm' : 'Thêm sản phẩm mới'}</h1>
        </div>
      </div>
      {product?.moderationRemoved && (
        <p className="account-notice account-notice--warning" role="status">
          Sản phẩm đã bị Admin gỡ: {product.moderationReason || 'không có lý do'}. Sau khi chỉnh
          sửa, bấm “Gửi lại” ở danh sách sản phẩm để Admin xem xét.
        </p>
      )}
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          submit(PRODUCT_STATUS.active)
        }}
      >
        <ProductForm
          form={form}
          setForm={setForm}
          errors={errors}
          categories={context.categories}
          disabled={Boolean(saving)}
          onUploaded={(urls) => urls.forEach((url) => uploaded.current.add(url))}
        />
        {submitError && (
          <p className="form-message form-message--error" role="alert">
            {submitError}
          </p>
        )}
        <div className="seller-product-editor__actions">
          <Link className="account-button account-button--secondary" to="/seller/products">
            Huỷ
          </Link>
          <button
            className="account-button account-button--secondary"
            type="button"
            disabled={Boolean(saving)}
            onClick={() => submit(PRODUCT_STATUS.draft)}
          >
            {saving === PRODUCT_STATUS.draft ? 'Đang lưu…' : 'Lưu nháp'}
          </button>
          <button className="account-button" type="submit" disabled={Boolean(saving)}>
            {saving === PRODUCT_STATUS.active
              ? 'Đang lưu…'
              : editing
                ? 'Lưu & đăng bán'
                : 'Đăng bán'}
          </button>
        </div>
      </form>
    </section>
  )
}
