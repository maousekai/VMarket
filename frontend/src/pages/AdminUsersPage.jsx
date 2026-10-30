import { useEffect, useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { validateUserSearch } from '../auth/validation'
import { LockDialog, StatusBadges, UnlockDialog, UserDrawer } from '../components/AdminUserDialogs'
import { AccountAvatar } from '../components/AppHeader'
import { getApiError } from '../services/api/apiClient'
import { searchUsers } from '../services/api/userApi'
import { displayName, roleLabels } from '../utils/adminUsers'
import { formatDate } from '../utils/formatDate'
import '../components/account.css'
import '../components/adminUsers.css'

const PAGE_SIZE = 10
const statusTabs = [
  ['', 'Tất cả'],
  ['ACTIVE', 'Đang hoạt động'],
  ['LOCKED', 'Đã khóa'],
]
const tableColumns = [
  'Họ tên & thành viên',
  'Email / SĐT',
  'Vai trò',
  'Ngày tham gia',
  'Trạng thái',
]

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

export default function AdminUsersPage() {
  const { session } = useAuth()
  const [filters, setFilters] = useState({ q: '', status: '', page: 0 })
  const [draft, setDraft] = useState('')
  const [draftError, setDraftError] = useState('')
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [reloadKey, setReloadKey] = useState(0)
  const [selected, setSelected] = useState(null)
  const [action, setAction] = useState(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let current = true
    searchUsers({ q: filters.q, status: filters.status, page: filters.page, size: PAGE_SIZE })
      .then((result) => {
        if (!current) return
        // Trang cuối vừa rỗng sau khi khoá/mở khoá hoặc đổi bộ lọc (bản ghi cuối bị
        // đẩy sang trang khác): lùi về trang cuối còn dữ liệu thay vì hiện bảng trống.
        if (result.items.length === 0 && result.page > 0) {
          const page = Math.max(result.totalPages - 1, 0)
          if (page !== result.page) {
            setState((previous) => ({ ...previous, status: 'loading' }))
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
    const errors = validateUserSearch({ q: draft })
    setDraftError(errors.q || '')
    if (errors.q) return
    apply({ ...filters, q: draft.trim(), page: 0 })
  }

  function clearSearch() {
    setDraft('')
    setDraftError('')
    apply({ ...filters, q: '', page: 0 })
  }

  function onChanged(updated, message) {
    setSelected(updated)
    setAction(null)
    setNotice(message)
    setReloadKey((key) => key + 1)
  }

  const { result } = state
  const loading = state.status === 'loading'
  const from = result ? result.page * result.size + 1 : 0
  const to = result ? result.page * result.size + result.items.length : 0

  return (
    <>
      <section className="admin-users" aria-labelledby="admin-users-heading">
        <div className="admin-users__header">
          <span className="account-eyebrow">Điều hành & quản trị</span>
          <h1 id="admin-users-heading">Quản lý người dùng toàn sàn</h1>
          <p className="account-subtitle">
            Kiểm soát danh tính, phân quyền và trạng thái hoạt động của các bên tham gia sàn
            VMarket.
          </p>
        </div>

        <div className="admin-users__toolbar">
          <div className="admin-users__tabs" role="group" aria-label="Lọc theo trạng thái">
            {statusTabs.map(([value, label]) => (
              <button
                key={label}
                className="admin-users__tab"
                type="button"
                aria-pressed={filters.status === value}
                onClick={() => apply({ ...filters, status: value, page: 0 })}
              >
                {label}
              </button>
            ))}
          </div>
          <form className="admin-users__search" role="search" noValidate onSubmit={onSearch}>
            <input
              type="search"
              aria-label="Tìm kiếm người dùng"
              aria-invalid={draftError ? 'true' : undefined}
              aria-describedby={draftError ? 'admin-users-search-error' : undefined}
              placeholder="Tìm tên, email, SĐT…"
              maxLength={200}
              value={draft}
              onChange={(event) => {
                setDraft(event.target.value)
                setDraftError('')
              }}
            />
            <button className="account-button" type="submit">
              Tìm kiếm
            </button>
            {filters.q && (
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
        {draftError && (
          <p className="account-field__error" id="admin-users-search-error" role="alert">
            {draftError}
          </p>
        )}
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
              onClick={() => {
                setState({ status: 'loading', result: null, error: '' })
                setReloadKey((key) => key + 1)
              }}
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
                <p className="admin-users__empty">Không tìm thấy người dùng phù hợp.</p>
              </div>
            ) : (
              <div
                className={`admin-users__table-wrap${loading ? ' is-loading' : ''}`}
                aria-busy={loading ? 'true' : undefined}
              >
                <table className="admin-table">
                  <caption className="visually-hidden">Danh sách người dùng</caption>
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
                    {result.items.map((user) => (
                      <tr key={user.userId} data-locked={user.status === 'LOCKED'}>
                        <td>
                          <div className="admin-user">
                            <AccountAvatar name={displayName(user)} avatarUrl={user.avatarUrl} />
                            <div>
                              <button
                                className="admin-user__name"
                                type="button"
                                onClick={() => {
                                  setNotice('')
                                  setSelected(user)
                                }}
                              >
                                {displayName(user)}
                              </button>
                              <span className="admin-user__id" title={user.userId}>
                                ID: #{user.userId.slice(-8)}
                              </span>
                            </div>
                          </div>
                        </td>
                        <td>
                          {user.email}
                          {user.phone && <span className="admin-cell__sub">{user.phone}</span>}
                        </td>
                        <td>
                          <span className="admin-roles">
                            {user.roles.map((role) => (
                              <span key={role} className="role-badge">
                                {roleLabels[role] || role}
                              </span>
                            ))}
                          </span>
                        </td>
                        <td>{formatDate(user.createdAt)}</td>
                        <td>
                          <StatusBadges user={user} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="admin-users__footer">
              <span>
                Hiển thị {from}–{to} trong số {result.totalElements} người dùng
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
            Đang tải danh sách người dùng…
          </p>
        )}
      </section>

      {selected && (
        <UserDrawer
          user={selected}
          isSelf={selected.userId === session.userId}
          refreshKey={reloadKey}
          onClose={() => setSelected(null)}
          onLock={() => setAction('lock')}
          onUnlock={() => setAction('unlock')}
        />
      )}
      {selected && action === 'lock' && (
        <LockDialog
          user={selected}
          onCancel={() => setAction(null)}
          onLocked={(updated) =>
            onChanged(updated, `Đã khóa tài khoản của ${displayName(updated)}.`)
          }
        />
      )}
      {selected && action === 'unlock' && (
        <UnlockDialog
          user={selected}
          onCancel={() => setAction(null)}
          onUnlocked={(updated) =>
            onChanged(updated, `Đã mở khóa tài khoản của ${displayName(updated)}.`)
          }
        />
      )}
    </>
  )
}
