import { useEffect, useState } from 'react'
import { AdminShopDecisionModal, AdminShopDrawer } from '../components/AdminShopDialogs'
import { AccountAvatar } from '../components/AppHeader'
import ShopStatusBadge from '../components/ShopStatusBadge'
import { getApiError } from '../services/api/apiClient'
import { getShopCounts, searchAdminShops } from '../services/api/shopApi'
import { formatDate } from '../utils/formatDate'
import { imageUrlOrEmpty } from '../utils/shop'
import '../components/account.css'
import '../components/adminUsers.css'
import '../components/adminShops.css'

const PAGE_SIZE = 10
const statusTabs = [
  ['PENDING', 'Chờ duyệt'],
  ['ACTIVE', 'Đang hoạt động'],
  ['REJECTED', 'Bị từ chối'],
  ['SUSPENDED', 'Đình chỉ'],
  ['', 'Tất cả'],
]
const tableColumns = ['Gian hàng', 'Chủ gian hàng', 'Liên hệ', 'Ngày nộp', 'Trạng thái']

function ShopLogo({ shop }) {
  const logo = imageUrlOrEmpty(shop.logoUrl)
  if (logo) return <img src={logo} alt="" className="admin-shop-logo" />
  return (
    <span className="admin-shop-logo" aria-hidden="true">
      {shop.name?.trim().charAt(0).toLocaleUpperCase('vi') || '?'}
    </span>
  )
}

function TableSkeleton() {
  return (
    <div className="admin-users__table-wrap" aria-busy="true">
      <table className="admin-table" aria-hidden="true">
        <thead>
          <tr>
            {tableColumns.map((column) => (
              <th key={column} scope="col">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: 5 }, (_, row) => (
            <tr key={row}>
              {tableColumns.map((column) => (
                <td key={column}>
                  <span className="admin-skeleton" />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function AdminShopsPage() {
  const [filters, setFilters] = useState({ status: 'PENDING', keyword: '', page: 0 })
  const [draft, setDraft] = useState('')
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [counts, setCounts] = useState(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [selected, setSelected] = useState(null)
  const [decision, setDecision] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let current = true
    getShopCounts()
      .then((result) => {
        if (current) setCounts(result)
      })
      .catch(() => {
        if (current) setCounts(null)
      })
    return () => {
      current = false
    }
  }, [reloadKey])

  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, status: 'loading' }))
    searchAdminShops({ ...filters, size: PAGE_SIZE })
      .then((result) => {
        if (!current) return
        if (result.items.length === 0 && result.page > 0) {
          const page = Math.max(result.totalPages - 1, 0)
          if (page !== result.page) {
            setFilters((previous) => ({ ...previous, page }))
            return
          }
        }
        setState({ status: 'ready', result, error: '' })
      })
      .catch((failure) => {
        if (current)
          setState({ status: 'error', result: null, error: getApiError(failure).message })
      })
    return () => {
      current = false
    }
  }, [filters, reloadKey])

  function apply(next) {
    setState((previous) => ({ ...previous, status: 'loading' }))
    setFilters(next)
  }

  function onSearch(event) {
    event.preventDefault()
    apply({ ...filters, keyword: draft.trim(), page: 0 })
  }

  function clearSearch() {
    setDraft('')
    apply({ ...filters, keyword: '', page: 0 })
  }

  function onDecided(updated) {
    setSelected(updated)
    setDecision('')
    setNotice(
      updated.status === 'ACTIVE'
        ? `Đã cập nhật trạng thái “Đang hoạt động” cho ${updated.name}.`
        : updated.status === 'REJECTED'
          ? `Đã từ chối hồ sơ ${updated.name}.`
          : updated.status === 'SUSPENDED'
            ? `Đã đình chỉ gian hàng ${updated.name}.`
            : 'Đã cập nhật gian hàng.',
    )
    setReloadKey((key) => key + 1)
  }

  const result = state.result
  const loading = state.status === 'loading'
  const from = result ? result.page * result.size + 1 : 0
  const to = result ? result.page * result.size + result.items.length : 0
  const countFor = (status) => (status ? counts?.[status.toLowerCase()] : counts?.total)

  return (
    <>
      <section className="admin-shops" aria-labelledby="admin-shops-heading">
        <div className="admin-users__header admin-shops__header">
          <div>
            <span className="account-eyebrow">Điều hành & quản trị</span>
            <h1 id="admin-shops-heading">Phê duyệt gian hàng</h1>
            <p className="account-subtitle">
              Kiểm tra hồ sơ người bán, duyệt gian hàng mới và xử lý vi phạm.
            </p>
          </div>
          {counts && (
            <div className="admin-shops__summary" aria-label="Tổng quan gian hàng">
              <strong>{counts.total}</strong>
              <span>gian hàng</span>
              <span className="admin-shops__pending-badge">{counts.pending} chờ duyệt</span>
            </div>
          )}
        </div>

        <div className="admin-users__toolbar">
          <div className="admin-users__tabs" role="group" aria-label="Lọc theo trạng thái">
            {statusTabs.map(([value, label]) => (
              <button
                key={label}
                className="admin-users__tab"
                type="button"
                aria-pressed={filters.status === value}
                onClick={() => {
                  setNotice('')
                  apply({ ...filters, status: value, page: 0 })
                }}
              >
                {label}
                {counts && <span className="admin-shops__count">{countFor(value)}</span>}
              </button>
            ))}
          </div>
          <form className="admin-users__search" role="search" noValidate onSubmit={onSearch}>
            <input
              type="search"
              aria-label="Tìm kiếm gian hàng"
              placeholder="Tìm tên gian hàng…"
              maxLength={100}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
            />
            <button className="account-button" type="submit">
              Tìm kiếm
            </button>
            {filters.keyword && (
              <button
                className="account-button account-button--secondary"
                type="button"
                onClick={clearSearch}
              >
                Xóa tìm kiếm
              </button>
            )}
          </form>
        </div>

        {notice && (
          <p className="account-notice account-notice--success" role="status">
            {notice}
          </p>
        )}

        {loading && !result && <TableSkeleton />}
        {state.status === 'error' && (
          <section className="account-card account-state">
            <p role="alert">{state.error}</p>
            <button
              className="account-button"
              type="button"
              onClick={() => setReloadKey((key) => key + 1)}
            >
              Thử lại
            </button>
          </section>
        )}
        {result && state.status !== 'error' && (
          <>
            {result.items.length === 0 ? (
              <div
                className={`admin-users__table-wrap${loading ? ' is-loading' : ''}`}
                aria-busy={loading ? 'true' : undefined}
              >
                <p className="admin-users__empty">
                  {filters.keyword
                    ? 'Không tìm thấy gian hàng phù hợp.'
                    : 'Không có gian hàng ở trạng thái này.'}
                </p>
              </div>
            ) : (
              <div
                className={`admin-users__table-wrap${loading ? ' is-loading' : ''}`}
                aria-busy={loading ? 'true' : undefined}
              >
                <table className="admin-table admin-shops__table">
                  <caption className="visually-hidden">Danh sách gian hàng</caption>
                  <thead>
                    <tr>
                      {tableColumns.map((column) => (
                        <th key={column} scope="col">
                          {column}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {result.items.map((shop) => (
                      <tr key={shop.id}>
                        <td>
                          <div className="admin-shop-cell">
                            <ShopLogo shop={shop} />
                            <div>
                              <button
                                className="admin-user__name"
                                type="button"
                                onClick={() => {
                                  setNotice('')
                                  setSelected(shop)
                                }}
                              >
                                {shop.name}
                              </button>
                            </div>
                          </div>
                        </td>
                        <td>
                          <span className="admin-shop-owner">
                            <AccountAvatar
                              name={shop.ownerName || 'Người bán'}
                              avatarUrl={shop.ownerAvatarUrl}
                            />
                            <span>{shop.ownerName || 'Chưa có tên'}</span>
                          </span>
                        </td>
                        <td>
                          {shop.contactEmail}
                          <span className="admin-cell__sub">{shop.contactPhone}</span>
                        </td>
                        <td>{formatDate(shop.createdAt)}</td>
                        <td>
                          <ShopStatusBadge status={shop.status} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="admin-users__footer">
              <span>
                Hiển thị {from}–{to} trong số {result.totalElements} gian hàng
              </span>
              <div className="admin-users__pager">
                <button
                  className="account-button account-button--secondary"
                  type="button"
                  disabled={loading || result.page === 0}
                  onClick={() => apply({ ...filters, page: filters.page - 1 })}
                >
                  Trước
                </button>
                <span>
                  Trang {result.page + 1} / {Math.max(result.totalPages, 1)}
                </span>
                <button
                  className="account-button account-button--secondary"
                  type="button"
                  disabled={loading || result.page + 1 >= result.totalPages}
                  onClick={() => apply({ ...filters, page: filters.page + 1 })}
                >
                  Sau
                </button>
              </div>
            </div>
          </>
        )}
        {loading && (
          <p className="visually-hidden" role="status">
            Đang tải danh sách gian hàng…
          </p>
        )}
      </section>

      {selected && (
        <AdminShopDrawer
          key={`${selected.id}-${selected.status}-${selected.updatedAt || ''}`}
          shop={selected}
          onClose={() => {
            setSelected(null)
            setDecision('')
          }}
          onDecision={(mode) => setDecision(mode)}
        />
      )}
      {selected && decision && (
        <AdminShopDecisionModal
          mode={decision}
          shop={selected}
          onCancel={() => setDecision('')}
          onDone={onDecided}
        />
      )}
    </>
  )
}
