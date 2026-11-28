import { useEffect, useState } from 'react'
import AccountField from '../components/AccountField'
import DeliveryTrackingPanel from '../components/DeliveryTrackingPanel'
import Modal from '../components/Modal'
import { getApiError } from '../services/api/apiClient'
import {
  adminRetryAssignment,
  assignShipper,
  createShipper,
  getDelivery,
  listAssignments,
  listShippers,
  newIdempotencyKey,
  resendActivationEmail,
  returnAssignment,
  setShipperLocked,
} from '../services/api/deliveryApi'
import {
  DELIVERY_STATUS_LABELS,
  displayArea,
  displayAreaName,
  isAreaMismatch,
  NEEDS_DISPATCH_STATUS,
  PICKED_UP_STATUSES,
  SHIPPER_ACCOUNT_LABELS,
  SHIPPER_DISTRICTS,
  SHIPPER_PROVINCES,
  shipperAccountStatus,
} from '../utils/deliveryStatus'
import { formatDateTime } from '../utils/formatDate'
import { shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/delivery.css'

const PAGE_SIZE = 20
const STATUS_FILTERS = [
  ['', 'Tất cả trạng thái'],
  ...Object.entries(DELIVERY_STATUS_LABELS).filter(([status]) => status !== 'RETURN_DROPPED_OFF'),
]

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

function AccountBadge({ shipper }) {
  const status = shipperAccountStatus(shipper)
  return (
    <span className={`delivery-badge delivery-badge--${status.toLowerCase()}`}>
      {SHIPPER_ACCOUNT_LABELS[status]}
    </span>
  )
}

const emptyShipper = {
  email: '',
  username: '',
  password: '',
  name: '',
  phone: '',
  province: SHIPPER_PROVINCES[0],
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
          province: form.province,
          district: form.district || null,
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
    form.province &&
    form.district
  return (
    <Modal
      title="Thêm shipper"
      description="Tạo tài khoản vai trò SHIPPER. Shipper ở trạng thái “Chờ kích hoạt” cho tới khi xác thực email, và chỉ được phân công khi đã Hoạt động."
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
        {/* Chon tu danh sach: go sai chinh ta thi shipper khong bao gio duoc tu dong phan cong. */}
        <AccountField as="select" label="Tỉnh / thành phố" required {...field('province')}>
          {SHIPPER_PROVINCES.map((province) => (
            <option key={province} value={province}>
              {province}
            </option>
          ))}
        </AccountField>
        <AccountField as="select" label="Khu phụ trách" required {...field('district')}>
          <option value="">Chọn khu giao hàng</option>
          {SHIPPER_DISTRICTS.map((district) => (
            <option key={district} value={district}>
              {district}
            </option>
          ))}
        </AccountField>
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

function LockDialog({ shipper, onClose, onDone }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const locking = shipperAccountStatus(shipper) !== 'LOCKED'
  const open = Number(shipper.active_orders || 0)

  async function confirm() {
    setBusy(true)
    setError('')
    try {
      await setShipperLocked(shipper.id, locking)
      onDone(`${locking ? 'Đã khoá' : 'Đã mở khoá'} shipper ${shipper.name}.`)
    } catch (failure) {
      setError(getApiError(failure).message)
      setBusy(false)
    }
  }

  return (
    <Modal
      title={`${locking ? 'Khoá' : 'Mở khoá'} shipper ${shipper.name}?`}
      description={
        locking
          ? open > 0
            ? `Shipper đang có ${open} chuyến chưa xong. Sau khi khoá, shipper không đăng nhập và không gửi vị trí được nữa; hãy phân công lại các chuyến này.`
            : 'Shipper không có chuyến nào đang chạy. Sau khi khoá, shipper không đăng nhập được nữa.'
          : 'Shipper sẽ đăng nhập và nhận chuyến lại được.'
      }
      onClose={onClose}
    >
      {error && (
        <p className="account-notice account-notice--error" role="alert">
          {error}
        </p>
      )}
      <div className="account-actions">
        <button
          type="button"
          className={`account-button${locking ? ' account-button--danger' : ''}`}
          disabled={busy}
          onClick={confirm}
        >
          {busy ? 'Đang xử lý…' : locking ? 'Khoá shipper' : 'Mở khoá'}
        </button>
        <button
          type="button"
          className="account-button account-button--secondary"
          data-autofocus
          onClick={onClose}
        >
          Huỷ
        </button>
      </div>
    </Modal>
  )
}

function ShippersTab() {
  const [district, setDistrict] = useState('')
  const [creating, setCreating] = useState(false)
  const [locking, setLocking] = useState(null)
  const [notice, setNotice] = useState('')
  const shippers = usePaged((page) => listShippers({ district, page, size: PAGE_SIZE }), [district])

  async function resend(shipper) {
    setNotice('')
    try {
      await resendActivationEmail(shipper.id)
      setNotice(`Đã gửi lại email kích hoạt cho ${shipper.name}.`)
    } catch (failure) {
      setNotice(getApiError(failure).message)
    }
  }

  return (
    <>
      <div className="admin-users__toolbar">
        <label className="delivery-filter">
          <span>Khu giao hàng</span>
          <select
            value={district}
            onChange={(event) => {
              shippers.setPage(0)
              setDistrict(event.target.value)
            }}
          >
            <option value="">Tất cả</option>
            {SHIPPER_DISTRICTS.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
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
                  <th scope="col">Đang chạy</th>
                  <th scope="col">Đã giao / tổng</th>
                  <th scope="col">Tỉ lệ thành công</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">
                    <span className="visually-hidden">Thao tác</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {shippers.items.map((shipper) => {
                  const locked = shipperAccountStatus(shipper) === 'LOCKED'
                  return (
                    <tr key={shipper.id}>
                      <td>
                        <strong>{shipper.name}</strong>
                        <span className="admin-cell__sub">{shipper.phone}</span>
                      </td>
                      <td>{displayArea(shipper)}</td>
                      <td>{Number(shipper.active_orders || 0)}</td>
                      <td>
                        {shipper.delivered_orders} / {shipper.total_orders}
                      </td>
                      <td>{Number(shipper.success_rate_percent || 0).toFixed(1)}%</td>
                      <td>
                        <AccountBadge shipper={shipper} />
                      </td>
                      <td>
                        <div className="delivery-row-actions">
                          {shipperAccountStatus(shipper) === 'PENDING_ACTIVATION' && (
                            <button
                              type="button"
                              className="account-button account-button--secondary"
                              onClick={() => resend(shipper)}
                            >
                              Gửi lại email kích hoạt
                            </button>
                          )}
                          <button
                            type="button"
                            className={`account-button${locked ? ' account-button--secondary' : ' account-button--danger'}`}
                            onClick={() => setLocking(shipper)}
                          >
                            {locked ? 'Mở khoá' : 'Khoá'}
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
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
            setNotice(
              `Đã tạo shipper ${shipper.name}. Tài khoản ở trạng thái “Chờ kích hoạt” cho tới khi shipper xác thực email.`,
            )
            shippers.reload()
          }}
        />
      )}
      {locking && (
        <LockDialog
          shipper={locking}
          onClose={() => setLocking(null)}
          onDone={(message) => {
            setLocking(null)
            setNotice(message)
            shippers.reload()
          }}
        />
      )}
    </>
  )
}

function AssignDialog({ assignment, onClose, onDone }) {
  const [shippers, setShippers] = useState(null)
  const [version, setVersion] = useState(
    typeof assignment.version === 'number' ? assignment.version : null,
  )
  const [form, setForm] = useState({ shipperId: '', reason: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let current = true
    // Ca tinh, de admin thay ca shipper khu khac (gan nhan "Khac khu") khi khu het nguoi.
    const loads = [listShippers({ province: assignment.province, size: 100 })]
    if (version === null) loads.push(getDelivery(assignment.order_id))
    Promise.all(loads)
      .then(([items, detail]) => {
        if (!current) return
        const active = (items || [])
          .filter((shipper) => shipperAccountStatus(shipper) === 'ACTIVE')
          .filter((shipper) => shipper.id !== assignment.shipper_id)
          .map((shipper) => ({ ...shipper, mismatch: isAreaMismatch(shipper, assignment) }))
          .sort((a, b) => Number(a.mismatch) - Number(b.mismatch))
        setShippers(active)
        if (detail) setVersion(detail.assignment?.version ?? null)
      })
      .catch((failure) => current && setError(getApiError(failure).message))
    return () => {
      current = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assignment])

  const selected = shippers?.find((shipper) => shipper.id === form.shipperId)
  // Ly do (noi bo) bat buoc dung theo backend: gan shipper khac khu.
  const mismatch = Boolean(selected?.mismatch)
  const needsReason = mismatch

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      await assignShipper(assignment.id, {
        shipperId: form.shipperId,
        expectedVersion: version,
        overrideArea: mismatch || undefined,
        reason: form.reason.trim() || undefined,
      })
      onDone(`Đã phân công ${selected?.name || 'shipper'}.`)
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={`Phân công đơn ${shortOrderId(assignment.order_id)}`}
      description={`Khu vực giao: ${displayArea(assignment)}${assignment.shipper_name ? ` · Đang giao cho ${assignment.shipper_name}` : ''}`}
      onClose={onClose}
    >
      <form className="profile-form" noValidate onSubmit={submit}>
        <AccountField
          as="select"
          label="Shipper (chỉ tài khoản đang hoạt động)"
          required
          data-autofocus
          value={form.shipperId}
          onChange={(event) =>
            setForm((previous) => ({ ...previous, shipperId: event.target.value }))
          }
        >
          <option value="">
            {shippers
              ? shippers.length
                ? 'Chọn shipper'
                : 'Không có shipper đang hoạt động'
              : 'Đang tải…'}
          </option>
          {(shippers || []).map((shipper) => (
            <option key={shipper.id} value={shipper.id}>
              {shipper.name} · {displayAreaName(shipper.district) || 'Cả tỉnh'} ·{' '}
              {Number(shipper.active_orders || 0)} chuyến đang chạy
              {shipper.mismatch ? ' · Khác khu' : ''}
            </option>
          ))}
        </AccountField>
        {mismatch && (
          <p className="account-notice account-notice--warning" role="status">
            Shipper này không phụ trách khu của đơn. Cần ghi lý do để gán khác khu.
          </p>
        )}
        {needsReason && (
          <AccountField
            as="textarea"
            label="Lý do (nội bộ, người mua không thấy)"
            required
            maxLength={255}
            hint="Bắt buộc khi gán shipper khác khu."
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
      const version =
        assignment.version ?? (await getDelivery(assignment.order_id)).assignment.version
      await returnAssignment(assignment.id, { expectedVersion: version, reason: reason.trim() })
      onDone('Đã chuyển đơn sang hoàn hàng.')
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Modal
      title={`Chuyển hoàn hàng đơn ${shortOrderId(assignment.order_id)}`}
      description="Hàng đã rời shop nên không đổi shipper được; chuyến sẽ chuyển sang hoàn hàng về các shop."
      onClose={onClose}
    >
      <form className="profile-form" noValidate onSubmit={submit}>
        <AccountField
          as="textarea"
          label="Lý do (nội bộ)"
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
  const [status, setStatus] = useState('')
  const assignments = usePaged(
    (page) => listAssignments({ status, page, size: PAGE_SIZE }),
    [status],
  )
  const waiting = usePaged(
    () => listAssignments({ status: NEEDS_DISPATCH_STATUS, page: 0, size: 100 }),
    [],
  )
  const [dialog, setDialog] = useState(null)
  const [notice, setNotice] = useState('')

  async function retry(assignment) {
    setNotice('')
    try {
      await adminRetryAssignment(assignment.id, assignment.version)
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
    waiting.reload()
  }

  const waitingCount = waiting.items.length

  return (
    <>
      {waitingCount > 0 && status !== NEEDS_DISPATCH_STATUS && (
        <div className="account-notice account-notice--warning" role="status">
          {waitingCount >= 100 ? '100+' : waitingCount} chuyến chưa có shipper (tự động phân công
          thất bại).{' '}
          <button
            type="button"
            className="shipper-topbar__link"
            onClick={() => {
              assignments.setPage(0)
              setStatus(NEEDS_DISPATCH_STATUS)
            }}
          >
            Xem và phân công
          </button>
        </div>
      )}
      <div className="admin-users__toolbar">
        <label className="delivery-filter">
          <span>Trạng thái</span>
          <select
            value={status}
            onChange={(event) => {
              assignments.setPage(0)
              setStatus(event.target.value)
            }}
          >
            {STATUS_FILTERS.map(([value, label]) => (
              <option key={value || 'all'} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>
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
            <p className="admin-users__empty">Không có chuyến giao nào.</p>
          ) : (
            <table className="admin-table">
              <caption className="visually-hidden">Danh sách chuyến giao</caption>
              <thead>
                <tr>
                  <th scope="col">Đơn</th>
                  <th scope="col">Shipper</th>
                  <th scope="col">Phường / khu</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">Lần giao</th>
                  <th scope="col">Tạo lúc</th>
                  <th scope="col">
                    <span className="visually-hidden">Thao tác</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {assignments.items.map((assignment) => {
                  const waitingDispatch = assignment.status === NEEDS_DISPATCH_STATUS
                  const flagged = Boolean(assignment.needs_attention)
                  return (
                    <tr
                      key={assignment.id}
                      className={waitingDispatch || flagged ? 'delivery-row--attention' : undefined}
                    >
                      <td>
                        <strong>{shortOrderId(assignment.order_id)}</strong>
                        {waitingDispatch && (
                          <span className="delivery-attention">Cần phân công</span>
                        )}
                        {flagged && (
                          <span className="delivery-attention delivery-attention--flag">
                            Cần xử lý: shipper bị khoá khi đang giữ hàng
                          </span>
                        )}
                      </td>
                      <td>
                        {assignment.shipper_name ? (
                          <>
                            {assignment.shipper_name}
                            <span className="admin-cell__sub">{assignment.shipper_phone}</span>
                          </>
                        ) : (
                          <span className="admin-cell__sub">Chưa có shipper</span>
                        )}
                      </td>
                      <td>{displayArea(assignment)}</td>
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
                          {/* Chi doi shipper truoc khi lay hang (review PR 68 lan 2, muc 4.6). */}
                          {['WAITING_ASSIGNMENT', 'ASSIGNED'].includes(assignment.status) && (
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
                          {PICKED_UP_STATUSES.includes(assignment.status) && (
                            <button
                              type="button"
                              className="account-button account-button--danger"
                              onClick={() => setDialog({ kind: 'return', assignment })}
                            >
                              Chuyển hoàn hàng
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
                  )
                })}
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
          <DeliveryTrackingPanel orderId={dialog.assignment.order_id} audience="admin" />
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
