import { useEffect, useState } from 'react'
import AccountField from '../components/AccountField'
import DeliveryTrackingPanel from '../components/DeliveryTrackingPanel'
import Modal from '../components/Modal'
import { getApiError } from '../services/api/apiClient'
import {
  adminRetryAssignment,
  assignShipper,
  createShipper,
  DELIVERY_STATUS_LABELS,
  getDelivery,
  isTerminalStatus,
  listAssignments,
  listShippers,
  newIdempotencyKey,
  returnAssignment,
  setShipperLocked,
} from '../services/api/deliveryApi'
import { formatDateTime } from '../utils/formatDate'
import { shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/delivery.css'

const PAGE_SIZE = 20
const area = (row) => [row.district, row.province].filter(Boolean).join(', ') || '—'

function usePaged(loader, deps) {
  const [page, setPage] = useState(0)
  const [state, setState] = useState({ status: 'loading', items: [], error: '' })
  const [reloadKey, setReloadKey] = useState(0)
  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, status: 'loading' }))
    loader(page)
      .then((items) => current && setState({ status: 'ready', items: items || [], error: '' }))
      .catch(
        (failure) =>
          current && setState({ status: 'error', items: [], error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, reloadKey, ...deps])
  return { ...state, page, setPage, reload: () => setReloadKey((key) => key + 1) }
}

function Pager({ page, count, setPage }) {
  if (page === 0 && count < PAGE_SIZE) return null
  return (
    <div className="admin-users__pager">
      <button
        className="account-button account-button--secondary"
        type="button"
        disabled={page === 0}
        onClick={() => setPage(page - 1)}
      >
        Trang trước
      </button>
      <span>Trang {page + 1}</span>
      <button
        className="account-button account-button--secondary"
        type="button"
        disabled={count < PAGE_SIZE}
        onClick={() => setPage(page + 1)}
      >
        Trang sau
      </button>
    </div>
  )
}

const emptyShipper = {
  email: '',
  username: '',
  password: '',
  name: '',
  phone: '',
  province: '',
  district: '',
}

function CreateShipperDialog({ onClose, onCreated }) {
  const [form, setForm] = useState(emptyShipper)
  // Giu nguyen khoa khi bam lai: request dau da toi server cung khong tao trung shipper.
  const [key] = useState(newIdempotencyKey)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const field = (name) => ({
    value: form[name],
    error: error?.fieldErrors?.[name] || error?.fieldErrors?.[`account.${name}`],
    onChange: (event) => setForm((previous) => ({ ...previous, [name]: event.target.value })),
  })

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await createShipper(
        {
          account: {
            email: form.email.trim(),
            username: form.username.trim(),
            password: form.password,
          },
          name: form.name.trim(),
          phone: form.phone.trim(),
          province: form.province.trim(),
          district: form.district.trim() || null,
        },
        key,
      )
      onCreated(created)
    } catch (failure) {
      setError(getApiError(failure))
    } finally {
      setBusy(false)
    }
  }

  const ready =
    form.email &&
    form.username &&
    form.password.length >= 8 &&
    form.name &&
    form.phone &&
    form.province
  return (
    <Modal
      title="Thêm shipper"
      description="Tạo tài khoản đăng nhập vai trò SHIPPER và hồ sơ khu vực giao hàng."
      onClose={onClose}
    >
      <form className="profile-form" noValidate onSubmit={submit}>
        <AccountField label="Họ tên" required maxLength={100} data-autofocus {...field('name')} />
        <AccountField
          label="Số điện thoại"
          required
          type="tel"
          maxLength={20}
          {...field('phone')}
        />
        <AccountField label="Email" required type="email" maxLength={320} {...field('email')} />
        <AccountField
          label="Tên đăng nhập"
          required
          maxLength={50}
          hint="3–50 ký tự: chữ, số, dấu chấm, gạch dưới, gạch ngang."
          {...field('username')}
        />
        <AccountField
          label="Mật khẩu tạm"
          required
          type="password"
          minLength={8}
          maxLength={32}
          hint="8–32 ký tự. Shipper nên đổi sau lần đăng nhập đầu."
          {...field('password')}
        />
        <AccountField label="Tỉnh / thành phố" required maxLength={100} {...field('province')} />
        <AccountField label="Quận / huyện" maxLength={100} {...field('district')} />
        {error && !Object.keys(error.fieldErrors || {}).length && (
          <p className="account-notice account-notice--error" role="alert">
            {error.message}
          </p>
        )}
        <div className="account-actions">
          <button className="account-button" type="submit" disabled={busy || !ready}>
            {busy ? 'Đang tạo…' : 'Tạo shipper'}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={onClose}
          >
            Huỷ
          </button>
        </div>
      </form>
    </Modal>
  )
}

function ShippersTab() {
  const [province, setProvince] = useState('')
  const [filter, setFilter] = useState('')
  const [creating, setCreating] = useState(false)
  const [notice, setNotice] = useState('')
  const [busyId, setBusyId] = useState('')
  const shippers = usePaged(
    (page) => listShippers({ province: filter, page, size: PAGE_SIZE }),
    [filter],
  )

  async function toggleLock(shipper) {
    setBusyId(shipper.id)
    setNotice('')
    try {
      await setShipperLocked(shipper.id, shipper.active)
      setNotice(`${shipper.active ? 'Đã khoá' : 'Đã mở khoá'} shipper ${shipper.name}.`)
      shippers.reload()
    } catch (failure) {
      setNotice(getApiError(failure).message)
    } finally {
      setBusyId('')
    }
  }

  return (
    <>
      <div className="admin-users__toolbar">
        <form
          className="admin-users__search"
          role="search"
          onSubmit={(event) => {
            event.preventDefault()
            shippers.setPage(0)
            setFilter(province.trim())
          }}
        >
          <input
            type="search"
            aria-label="Lọc theo tỉnh / thành phố"
            placeholder="Lọc theo tỉnh / thành phố…"
            maxLength={100}
            value={province}
            onChange={(event) => setProvince(event.target.value)}
          />
          <button className="account-button" type="submit">
            Lọc
          </button>
        </form>
        <button className="account-button" type="button" onClick={() => setCreating(true)}>
          Thêm shipper
        </button>
      </div>
      {notice && (
        <p className="account-notice" role="status">
          {notice}
        </p>
      )}
      {shippers.status === 'error' && (
        <section className="account-card account-state">
          <p role="alert">{shippers.error}</p>
          <button className="account-button" type="button" onClick={shippers.reload}>
            Thử lại
          </button>
        </section>
      )}
      {shippers.status !== 'error' && (
        <div
          className={`admin-users__table-wrap${shippers.status === 'loading' ? ' is-loading' : ''}`}
          aria-busy={shippers.status === 'loading' ? 'true' : undefined}
        >
          {shippers.status === 'ready' && shippers.items.length === 0 ? (
            <p className="admin-users__empty">Chưa có shipper nào.</p>
          ) : (
            <table className="admin-table">
              <caption className="visually-hidden">Danh sách shipper</caption>
              <thead>
                <tr>
                  <th scope="col">Shipper</th>
                  <th scope="col">Khu vực</th>
                  <th scope="col">Đã giao / tổng</th>
                  <th scope="col">Tỉ lệ thành công</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">
                    <span className="visually-hidden">Thao tác</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {shippers.items.map((shipper) => (
                  <tr key={shipper.id}>
                    <td>
                      <strong>{shipper.name}</strong>
                      <span className="admin-cell__sub">{shipper.phone}</span>
                    </td>
                    <td>{area(shipper)}</td>
                    <td>
                      {shipper.delivered_orders} / {shipper.total_orders}
                    </td>
                    <td>{Number(shipper.success_rate_percent || 0).toFixed(1)}%</td>
                    <td>
                      <span
                        className={`delivery-badge delivery-badge--${shipper.active ? 'delivered' : 'failed'}`}
                      >
                        {shipper.active ? 'Hoạt động' : 'Đã khoá'}
                      </span>
                    </td>
                    <td>
                      <button
                        type="button"
                        className={`account-button${shipper.active ? ' account-button--danger' : ' account-button--secondary'}`}
                        disabled={busyId === shipper.id}
                        onClick={() => toggleLock(shipper)}
                      >
                        {shipper.active ? 'Khoá' : 'Mở khoá'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      <Pager page={shippers.page} count={shippers.items.length} setPage={shippers.setPage} />
      {creating && (
        <CreateShipperDialog
          onClose={() => setCreating(false)}
          onCreated={(shipper) => {
            setCreating(false)
            setNotice(`Đã tạo shipper ${shipper.name}.`)
            shippers.reload()
          }}
        />
      )}
    </>
  )
}

function AssignDialog({ assignment, onClose, onDone }) {
  const [shippers, setShippers] = useState(null)
  const [version, setVersion] = useState(null)
  const [form, setForm] = useState({
    shipperId: assignment.shipper_id || '',
    overrideArea: false,
    reason: '',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const midTrip = !['WAITING_ASSIGNMENT', 'ASSIGNED'].includes(assignment.status)

  useEffect(() => {
    let current = true
    // Danh sach chuyen khong tra version; lay tu chi tiet de chan ghi de (STALE_DELIVERY).
    Promise.all([
      listShippers({ province: form.overrideArea ? '' : assignment.province, size: 100 }),
      getDelivery(assignment.order_id),
    ])
      .then(([items, detail]) => {
        if (!current) return
        setShippers((items || []).filter((shipper) => shipper.active))
        setVersion(detail?.assignment?.version ?? null)
      })
      .catch((failure) => current && setError(getApiError(failure).message))
    return () => {
      current = false
    }
  }, [assignment, form.overrideArea])

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await assignShipper(assignment.id, {
        shipperId: form.shipperId,
        expectedVersion: version,
        overrideArea: form.overrideArea || undefined,
        reason: form.reason.trim() || undefined,
      })
      onDone('Đã phân công shipper.')
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setBusy(false)
    }
  }

  const needsReason = midTrip || form.overrideArea
  return (
    <Modal
      title={`Phân công đơn ${shortOrderId(assignment.order_id)}`}
      description={`Khu vực giao: ${area(assignment)}`}
      onClose={onClose}
    >
      <form className="profile-form" noValidate onSubmit={submit}>
        <AccountField
          as="select"
          label="Shipper"
          required
          data-autofocus
          value={form.shipperId}
          onChange={(event) =>
            setForm((previous) => ({ ...previous, shipperId: event.target.value }))
          }
        >
          <option value="">{shippers ? 'Chọn shipper' : 'Đang tải…'}</option>
          {(shippers || []).map((shipper) => (
            <option key={shipper.id} value={shipper.id}>
              {shipper.name} · {area(shipper)} ·{' '}
              {Number(shipper.success_rate_percent || 0).toFixed(0)}%
            </option>
          ))}
        </AccountField>
        <label className="delivery-check">
          <input
            type="checkbox"
            checked={form.overrideArea}
            onChange={(event) =>
              setForm((previous) => ({
                ...previous,
                overrideArea: event.target.checked,
                shipperId: '',
              }))
            }
          />
          Cho phép shipper ngoài khu vực
        </label>
        {needsReason && (
          <AccountField
            as="textarea"
            label="Lý do"
            required
            maxLength={255}
            hint={
              midTrip ? 'Bắt buộc khi đổi shipper giữa chuyến.' : 'Bắt buộc khi giao ngoài khu vực.'
            }
            value={form.reason}
            onChange={(event) =>
              setForm((previous) => ({ ...previous, reason: event.target.value }))
            }
          />
        )}
        {error && (
          <p className="account-notice account-notice--error" role="alert">
            {error}
          </p>
        )}
        <div className="account-actions">
          <button
            className="account-button"
            type="submit"
            disabled={
              busy || !form.shipperId || version === null || (needsReason && !form.reason.trim())
            }
          >
            {busy ? 'Đang phân công…' : 'Phân công'}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={onClose}
          >
            Huỷ
          </button>
        </div>
      </form>
    </Modal>
  )
}

function ReturnDialog({ assignment, onClose, onDone }) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const detail = await getDelivery(assignment.order_id)
      await returnAssignment(assignment.id, {
        expectedVersion: detail.assignment.version,
        reason: reason.trim(),
      })
      onDone('Đã chuyển đơn sang hoàn hàng.')
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      title={`Hoàn hàng đơn ${shortOrderId(assignment.order_id)}`}
      description="Đơn giao thất bại sẽ được chuyển về gian hàng."
      onClose={onClose}
    >
      <form className="profile-form" noValidate onSubmit={submit}>
        <AccountField
          as="textarea"
          label="Lý do"
          required
          maxLength={255}
          data-autofocus
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
        {error && (
          <p className="account-notice account-notice--error" role="alert">
            {error}
          </p>
        )}
        <div className="account-actions">
          <button
            className="account-button account-button--danger"
            type="submit"
            disabled={busy || !reason.trim()}
          >
            {busy ? 'Đang xử lý…' : 'Hoàn hàng'}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={onClose}
          >
            Huỷ
          </button>
        </div>
      </form>
    </Modal>
  )
}

function AssignmentsTab() {
  const assignments = usePaged((page) => listAssignments({ page, size: PAGE_SIZE }), [])
  const [dialog, setDialog] = useState(null)
  const [notice, setNotice] = useState('')

  async function retry(assignment) {
    setNotice('')
    try {
      const detail = await getDelivery(assignment.order_id)
      await adminRetryAssignment(assignment.id, detail.assignment.version)
      setNotice('Đã chuyển đơn sang giao lại.')
      assignments.reload()
    } catch (failure) {
      setNotice(getApiError(failure).message)
    }
  }

  const done = (message) => {
    setDialog(null)
    setNotice(message)
    assignments.reload()
  }

  return (
    <>
      {notice && (
        <p className="account-notice" role="status">
          {notice}
        </p>
      )}
      {assignments.status === 'error' && (
        <section className="account-card account-state">
          <p role="alert">{assignments.error}</p>
          <button className="account-button" type="button" onClick={assignments.reload}>
            Thử lại
          </button>
        </section>
      )}
      {assignments.status !== 'error' && (
        <div
          className={`admin-users__table-wrap${assignments.status === 'loading' ? ' is-loading' : ''}`}
          aria-busy={assignments.status === 'loading' ? 'true' : undefined}
        >
          {assignments.status === 'ready' && assignments.items.length === 0 ? (
            <p className="admin-users__empty">Chưa có chuyến giao nào.</p>
          ) : (
            <table className="admin-table">
              <caption className="visually-hidden">Danh sách chuyến giao</caption>
              <thead>
                <tr>
                  <th scope="col">Đơn</th>
                  <th scope="col">Khu vực</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">Lần giao</th>
                  <th scope="col">Tạo lúc</th>
                  <th scope="col">
                    <span className="visually-hidden">Thao tác</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {assignments.items.map((assignment) => (
                  <tr key={assignment.id}>
                    <td>
                      <strong>{shortOrderId(assignment.order_id)}</strong>
                      <span className="admin-cell__sub">
                        {assignment.shipper_id
                          ? `Shipper ${assignment.shipper_id.slice(-6)}`
                          : 'Chưa có shipper'}
                      </span>
                    </td>
                    <td>{area(assignment)}</td>
                    <td>
                      <span
                        className={`delivery-badge delivery-badge--${assignment.status.toLowerCase()}`}
                      >
                        {DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}
                      </span>
                    </td>
                    <td>{assignment.attempt || 1}/3</td>
                    <td>{formatDateTime(assignment.created_at)}</td>
                    <td>
                      <div className="delivery-row-actions">
                        {assignment.status !== 'WAITING_HANDOFF' &&
                          !isTerminalStatus(assignment.status) && (
                            <button
                              type="button"
                              className="account-button"
                              onClick={() => setDialog({ kind: 'assign', assignment })}
                            >
                              {assignment.shipper_id ? 'Đổi shipper' : 'Phân công'}
                            </button>
                          )}
                        {assignment.status === 'FAILED' && assignment.attempt < 3 && (
                          <button
                            type="button"
                            className="account-button account-button--secondary"
                            onClick={() => retry(assignment)}
                          >
                            Giao lại
                          </button>
                        )}
                        {assignment.status === 'FAILED' && (
                          <button
                            type="button"
                            className="account-button account-button--danger"
                            onClick={() => setDialog({ kind: 'return', assignment })}
                          >
                            Hoàn hàng
                          </button>
                        )}
                        <button
                          type="button"
                          className="account-button account-button--secondary"
                          onClick={() => setDialog({ kind: 'track', assignment })}
                        >
                          Theo dõi
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      <Pager
        page={assignments.page}
        count={assignments.items.length}
        setPage={assignments.setPage}
      />
      {dialog?.kind === 'assign' && (
        <AssignDialog
          assignment={dialog.assignment}
          onClose={() => setDialog(null)}
          onDone={done}
        />
      )}
      {dialog?.kind === 'return' && (
        <ReturnDialog
          assignment={dialog.assignment}
          onClose={() => setDialog(null)}
          onDone={done}
        />
      )}
      {dialog?.kind === 'track' && (
        <Modal
          variant="drawer"
          title={`Theo dõi đơn ${shortOrderId(dialog.assignment.order_id)}`}
          onClose={() => setDialog(null)}
        >
          <DeliveryTrackingPanel orderId={dialog.assignment.order_id} />
        </Modal>
      )}
    </>
  )
}

/** Admin: quan ly shipper va dieu phoi chuyen giao (PBL6-33). */
export default function AdminShippersPage() {
  const [tab, setTab] = useState('assignments')
  return (
    <section className="admin-users" aria-labelledby="admin-shippers-heading">
      <div className="admin-users__header">
        <div>
          <span className="account-eyebrow">Điều hành & quản trị</span>
          <h1 id="admin-shippers-heading">Điều phối Shipper</h1>
          <p className="account-subtitle">
            Quản lý đội shipper, phân công chuyến giao và theo dõi vị trí theo thời gian thực.
          </p>
        </div>
      </div>
      <div className="admin-users__tabs" role="group" aria-label="Chọn mục">
        {[
          ['assignments', 'Chuyến giao'],
          ['shippers', 'Shipper'],
        ].map(([value, label]) => (
          <button
            key={value}
            type="button"
            className="admin-users__tab"
            aria-pressed={tab === value}
            onClick={() => setTab(value)}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === 'assignments' ? <AssignmentsTab /> : <ShippersTab />}
    </section>
  )
}
