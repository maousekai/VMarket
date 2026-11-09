import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import Modal from '../components/Modal'
import Pagination from '../components/Pagination'
import { getApiError } from '../services/api/apiClient'
import {
  deleteProduct,
  listMySellerProducts,
  PRODUCT_STATUS,
  resubmitProduct,
  updateProduct,
} from '../services/api/productApi'
import { formatMoney } from '../utils/formatMoney'
import { productToRequest } from '../utils/productForm'
import { imageUrlOrEmpty } from '../utils/shop'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/seller.css'
import '../components/sellerProducts.css'

const PAGE_SIZE = 10
const TABS = [
  ['all', 'Tất cả'],
  ['ACTIVE', 'Đang bán'],
  ['DRAFT', 'Bản nháp'],
  ['HIDDEN', 'Đã ẩn'],
  ['removed', 'Bị Admin gỡ'],
  ['soldout', 'Hết hàng'],
]
const statusLabels = { ACTIVE: 'Đang bán', DRAFT: 'Bản nháp', HIDDEN: 'Đã ẩn' }
const countFormat = new Intl.NumberFormat('vi-VN')

const matchesTab = (product, tab) => {
  if (tab === 'all') return true
  if (tab === 'removed') return product.moderationRemoved
  if (tab === 'soldout') return (product.availableStock ?? 0) === 0
  return product.status === tab
}

function priceRange(product) {
  const min = formatMoney(product.minPrice, product.currency)
  return product.maxPrice > product.minPrice
    ? `${min} – ${formatMoney(product.maxPrice, product.currency)}`
    : min
}

function variantSummary(product) {
  const values = new Set()
  for (const variant of product.variants || []) {
    Object.values(variant.attributes || {}).forEach((value) => values.add(value))
  }
  return [...values].slice(0, 6).join(', ')
}

export default function SellerProductsPage() {
  const location = useLocation()
  const [state, setState] = useState({ status: 'loading', products: [], error: '' })
  const [reload, setReload] = useState(0)
  const [tab, setTab] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(0)
  const [notice, setNotice] = useState(location.state?.notice || '')
  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(null)

  useEffect(() => {
    let current = true
    listMySellerProducts()
      .then((products) => current && setState({ status: 'ready', products, error: '' }))
      .catch((failure) => {
        if (!current) return
        const apiError = getApiError(failure)
        setState({
          status: 'error',
          products: [],
          error:
            apiError.code === 'FORBIDDEN'
              ? 'Gian hàng cần được duyệt trước khi quản lý sản phẩm.'
              : apiError.message,
        })
      })
    return () => {
      current = false
    }
  }, [reload])

  const filtered = useMemo(() => {
    const term = keyword.trim().toLocaleLowerCase('vi')
    return state.products.filter(
      (product) =>
        matchesTab(product, tab) &&
        (!term ||
          product.name.toLocaleLowerCase('vi').includes(term) ||
          product.variants?.some((variant) => variant.sku.toLocaleLowerCase('vi').includes(term))),
    )
  }, [state.products, tab, keyword])
  const totalPages = Math.ceil(filtered.length / PAGE_SIZE)
  const currentPage = Math.min(page, Math.max(totalPages - 1, 0))
  const rows = filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)

  async function run(product, action, message) {
    if (busy) return
    setBusy(product.id)
    setActionError('')
    setNotice('')
    try {
      await action()
      setNotice(message)
      setReload((value) => value + 1)
    } catch (failure) {
      setActionError(getApiError(failure).message)
    } finally {
      setBusy('')
    }
  }

  const toggleVisibility = (product) => {
    const next =
      product.status === PRODUCT_STATUS.hidden ? PRODUCT_STATUS.active : PRODUCT_STATUS.hidden
    return run(
      product,
      () => updateProduct(product.id, productToRequest(product, next)),
      next === PRODUCT_STATUS.hidden
        ? `Đã ẩn “${product.name}” khỏi cửa hàng.`
        : `Đã hiển thị lại “${product.name}”.`,
    )
  }

  return (
    <section className="seller-page" aria-labelledby="seller-products-title">
      <div className="seller-page__header seller-page__header--row">
        <div>
          <span className="account-eyebrow">Kênh người bán</span>
          <h1 id="seller-products-title">Quản lý sản phẩm</h1>
          {state.status === 'ready' && (
            <p className="account-subtitle">{state.products.length} sản phẩm trong gian hàng</p>
          )}
        </div>
        <div className="seller-page__header-actions">
          <Link className="account-button" to="/seller/products/new">
            + Thêm sản phẩm
          </Link>
        </div>
      </div>

      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
        </p>
      )}
      {actionError && (
        <p className="account-notice account-notice--warning" role="alert">
          {actionError}
        </p>
      )}

      <div className="account-card seller-products__toolbar">
        <div className="seller-tabs" role="group" aria-label="Lọc sản phẩm">
          {TABS.map(([value, label]) => (
            <button
              key={value}
              type="button"
              aria-pressed={tab === value}
              onClick={() => {
                setTab(value)
                setPage(0)
              }}
            >
              {label}
              <span>{state.products.filter((product) => matchesTab(product, value)).length}</span>
            </button>
          ))}
        </div>
        <input
          type="search"
          className="seller-products__search"
          aria-label="Tìm theo tên hoặc SKU"
          placeholder="Tìm theo tên hoặc SKU…"
          value={keyword}
          onChange={(event) => {
            setKeyword(event.target.value)
            setPage(0)
          }}
        />
      </div>

      {state.status === 'loading' && (
        <p className="account-notice" role="status">
          Đang tải sản phẩm…
        </p>
      )}
      {state.status === 'error' && (
        <div className="account-card account-state">
          <p role="alert">{state.error}</p>
          <button
            className="account-button"
            type="button"
            onClick={() => setReload((value) => value + 1)}
          >
            Thử lại
          </button>
        </div>
      )}
      {state.status === 'ready' && filtered.length === 0 && (
        <div className="account-card account-state">
          <p>
            {state.products.length
              ? 'Không có sản phẩm phù hợp với bộ lọc.'
              : 'Gian hàng chưa có sản phẩm nào.'}
          </p>
          {!state.products.length && (
            <Link className="account-button" to="/seller/products/new">
              Đăng sản phẩm đầu tiên
            </Link>
          )}
        </div>
      )}
      {rows.length > 0 && (
        <div className="seller-table-wrap account-card">
          <table className="seller-table seller-products__table">
            <caption className="visually-hidden">Danh sách sản phẩm</caption>
            <thead>
              <tr>
                <th scope="col">Sản phẩm</th>
                <th scope="col">Phân loại</th>
                <th scope="col">Giá bán</th>
                <th scope="col">Tồn kho</th>
                <th scope="col">Đã bán</th>
                <th scope="col">Trạng thái</th>
                <th scope="col">
                  <span className="visually-hidden">Thao tác</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((product) => {
                const image = imageUrlOrEmpty(product.imageUrls?.[0])
                const stock = product.availableStock ?? 0
                return (
                  <tr key={product.id}>
                    <td>
                      <div className="seller-product-cell">
                        {image ? (
                          <img src={image} alt="" />
                        ) : (
                          <span className="seller-product-cell__placeholder" aria-hidden="true" />
                        )}
                        <div>
                          <strong>{product.name}</strong>
                          <small>SKU: {product.variants?.[0]?.sku}</small>
                        </div>
                      </div>
                    </td>
                    <td>
                      <strong>{product.variants?.length || 0} phân loại</strong>
                      <small className="seller-products__muted">{variantSummary(product)}</small>
                    </td>
                    <td className="seller-products__price">{priceRange(product)}</td>
                    <td className={stock === 0 ? 'seller-products__out' : ''}>
                      {countFormat.format(stock)}
                    </td>
                    <td>{countFormat.format(product.soldCount ?? 0)}</td>
                    <td>
                      {product.moderationRemoved ? (
                        <span
                          className="product-status product-status--removed"
                          title={product.moderationReason || undefined}
                        >
                          Bị Admin gỡ
                        </span>
                      ) : (
                        <span
                          className={`product-status product-status--${product.status.toLowerCase()}`}
                        >
                          {statusLabels[product.status] || product.status}
                        </span>
                      )}
                      {product.moderationRemoved && product.moderationReason && (
                        <small className="seller-products__muted">{product.moderationReason}</small>
                      )}
                    </td>
                    <td>
                      <div className="seller-products__actions">
                        <Link
                          className="link-button"
                          to={`/seller/products/${encodeURIComponent(product.id)}/edit`}
                        >
                          Sửa
                        </Link>
                        {product.moderationRemoved ? (
                          <button
                            type="button"
                            className="link-button"
                            disabled={busy === product.id}
                            onClick={() =>
                              run(
                                product,
                                () => resubmitProduct(product.id),
                                `Đã gửi lại “${product.name}” cho Admin xem xét.`,
                              )
                            }
                          >
                            Gửi lại
                          </button>
                        ) : (
                          product.status !== PRODUCT_STATUS.draft && (
                            <button
                              type="button"
                              className="link-button"
                              disabled={busy === product.id}
                              onClick={() => toggleVisibility(product)}
                            >
                              {product.status === PRODUCT_STATUS.hidden ? 'Hiện' : 'Ẩn'}
                            </button>
                          )
                        )}
                        <button
                          type="button"
                          className="link-button seller-products__danger"
                          disabled={busy === product.id}
                          onClick={() => setConfirmDelete(product)}
                        >
                          Xoá
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <Pagination page={currentPage} totalPages={totalPages} onChange={setPage} />

      {confirmDelete && (
        <Modal
          title="Xoá sản phẩm?"
          description={`“${confirmDelete.name}” sẽ bị gỡ khỏi gian hàng và không thể khôi phục.`}
          onClose={() => setConfirmDelete(null)}
        >
          <div className="account-actions">
            <button
              type="button"
              className="account-button account-button--secondary"
              data-autofocus
              onClick={() => setConfirmDelete(null)}
            >
              Giữ lại
            </button>
            <button
              type="button"
              className="account-button account-button--danger"
              onClick={() => {
                const product = confirmDelete
                setConfirmDelete(null)
                run(product, () => deleteProduct(product.id), `Đã xoá “${product.name}”.`)
              }}
            >
              Xoá sản phẩm
            </button>
          </div>
        </Modal>
      )}
    </section>
  )
}
