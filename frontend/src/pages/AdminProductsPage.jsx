import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import Modal from '../components/Modal'
import Pagination from '../components/Pagination'
import { getApiError } from '../services/api/apiClient'
import { listAdminProducts, moderateProduct } from '../services/api/productApi'
import { formatDate } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { imageUrlOrEmpty } from '../utils/shop'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/sellerProducts.css'
import '../components/adminCatalog.css'

const PAGE_SIZE = 10
const REASON_MAX = 1000
const TABS = [
  ['visible', 'Đang hiển thị'],
  ['removed', 'Đã gỡ'],
  ['all', 'Tất cả'],
]
const statusLabels = { ACTIVE: 'Đang bán', DRAFT: 'Bản nháp', HIDDEN: 'Seller ẩn' }

const matchesTab = (product, tab) =>
  tab === 'all' || (tab === 'removed' ? product.moderationRemoved : !product.moderationRemoved)

function ModerationDialog({ product, onClose, onDone }) {
  const removing = !product.moderationRemoved
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function onSubmit(event) {
    event.preventDefault()
    if (removing && !reason.trim()) return setError('Vui lòng nhập lý do gỡ sản phẩm.')
    setSaving(true)
    setError('')
    try {
      const updated = await moderateProduct(product.id, { removed: removing, reason })
      onDone(updated, removing)
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }

  return (
    <Modal
      title={removing ? 'Gỡ sản phẩm vi phạm' : 'Khôi phục sản phẩm'}
      description={
        removing
          ? `“${product.name}” sẽ bị ẩn khỏi cửa hàng. Người bán thấy lý do và có thể chỉnh sửa rồi gửi lại.`
          : `“${product.name}” sẽ hiển thị lại theo trạng thái người bán đã chọn.`
      }
      onClose={onClose}
    >
      <form className="admin-catalog__form" noValidate onSubmit={onSubmit}>
        <label>
          <span>{removing ? 'Lý do gỡ *' : 'Ghi chú (không bắt buộc)'}</span>
          <textarea
            data-autofocus
            rows={4}
            maxLength={REASON_MAX}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
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
          <button
            type="submit"
            className={`account-button${removing ? ' account-button--danger' : ''}`}
            disabled={saving}
          >
            {saving ? 'Đang lưu…' : removing ? 'Gỡ sản phẩm' : 'Khôi phục'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

export default function AdminProductsPage() {
  const [state, setState] = useState({ status: 'loading', products: [], error: '' })
  const [reload, setReload] = useState(0)
  const [tab, setTab] = useState('visible')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let current = true
    listAdminProducts()
      .then((products) => current && setState({ status: 'ready', products, error: '' }))
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', products: [], error: getApiError(failure).message }),
      )
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
          product.id.toLocaleLowerCase('vi').includes(term) ||
          product.shopId?.toLocaleLowerCase('vi').includes(term)),
    )
  }, [state.products, tab, keyword])
  const totalPages = Math.ceil(filtered.length / PAGE_SIZE)
  const currentPage = Math.min(page, Math.max(totalPages - 1, 0))
  const rows = filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)

  return (
    <section className="admin-catalog" aria-labelledby="admin-products-heading">
      <div className="admin-users__header">
        <div>
          <span className="account-eyebrow">Kiểm duyệt nội dung</span>
          <h1 id="admin-products-heading">Kiểm duyệt sản phẩm</h1>
          <p className="account-subtitle">
            Gỡ sản phẩm vi phạm chính sách và khôi phục sau khi người bán chỉnh sửa.
          </p>
        </div>
      </div>
      <div className="admin-users__toolbar">
        <div className="admin-users__tabs" role="group" aria-label="Lọc theo trạng thái">
          {TABS.map(([value, label]) => (
            <button
              key={value}
              type="button"
              className="admin-users__tab"
              aria-pressed={tab === value}
              onClick={() => {
                setTab(value)
                setPage(0)
              }}
            >
              {label} ({state.products.filter((product) => matchesTab(product, value)).length})
            </button>
          ))}
        </div>
        <div className="admin-users__search">
          <input
            type="search"
            aria-label="Tìm sản phẩm"
            placeholder="Tìm theo tên, mã sản phẩm hoặc mã gian hàng…"
            value={keyword}
            onChange={(event) => {
              setKeyword(event.target.value)
              setPage(0)
            }}
          />
        </div>
      </div>
      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
        </p>
      )}
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
      {state.status === 'ready' && rows.length === 0 && (
        <p className="admin-users__empty">Không có sản phẩm phù hợp.</p>
      )}
      {rows.length > 0 && (
        <div className="admin-users__table-wrap">
          <table className="admin-table">
            <caption className="visually-hidden">Sản phẩm cần kiểm duyệt</caption>
            <thead>
              <tr>
                <th scope="col">Sản phẩm</th>
                <th scope="col">Gian hàng</th>
                <th scope="col">Giá</th>
                <th scope="col">Cập nhật</th>
                <th scope="col">Trạng thái</th>
                <th scope="col">
                  <span className="visually-hidden">Thao tác</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((product) => {
                const image = imageUrlOrEmpty(product.imageUrls?.[0])
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
                          <small>{product.id}</small>
                        </div>
                      </div>
                    </td>
                    <td>
                      <Link to={`/shops/${encodeURIComponent(product.shopId)}`}>
                        {product.shopId}
                      </Link>
                    </td>
                    <td>{formatMoney(product.minPrice, product.currency)}</td>
                    <td>{formatDate(product.updatedAt)}</td>
                    <td>
                      {product.moderationRemoved ? (
                        <>
                          <span className="product-status product-status--removed">Đã gỡ</span>
                          <small className="seller-products__muted">
                            {product.moderationReason}
                          </small>
                        </>
                      ) : (
                        <span
                          className={`product-status product-status--${product.status.toLowerCase()}`}
                        >
                          {statusLabels[product.status] || product.status}
                        </span>
                      )}
                    </td>
                    <td>
                      <div className="admin-catalog__actions">
                        {product.status === 'ACTIVE' && !product.moderationRemoved && (
                          <Link
                            className="link-button"
                            to={`/products/${encodeURIComponent(product.id)}`}
                          >
                            Xem
                          </Link>
                        )}
                        <button
                          type="button"
                          className={`link-button${product.moderationRemoved ? '' : ' admin-catalog__danger'}`}
                          onClick={() => setSelected(product)}
                        >
                          {product.moderationRemoved ? 'Khôi phục' : 'Gỡ'}
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

      {selected && (
        <ModerationDialog
          product={selected}
          onClose={() => setSelected(null)}
          onDone={(updated, removed) => {
            setSelected(null)
            setNotice(removed ? `Đã gỡ “${updated.name}”.` : `Đã khôi phục “${updated.name}”.`)
            setReload((value) => value + 1)
          }}
        />
      )}
    </section>
  )
}
