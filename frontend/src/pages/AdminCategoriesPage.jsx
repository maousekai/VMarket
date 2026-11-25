import { useEffect, useState } from 'react'
import Modal from '../components/Modal'
import { getApiError } from '../services/api/apiClient'
import {
  createCategory,
  deleteCategory,
  listAdminCategories,
  setCategoryVisibility,
  updateCategory,
} from '../services/api/productApi'
import { descendantIds, findNode, flattenTree, sortTree } from '../utils/category'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/adminCatalog.css'

const NAME_MAX = 120

function CategoryDialog({ tree, category, parentId, onClose, onSaved }) {
  const editing = Boolean(category)
  const [form, setForm] = useState({
    name: category?.name || '',
    parentId: category ? category.parentId || '' : parentId || '',
    sortOrder: String(category?.sortOrder ?? 0),
    active: category?.active ?? true,
  })
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  // A category cannot move under itself or one of its descendants.
  const blocked = editing ? descendantIds(findNode(tree, category.id)) : new Set()
  const parents = flattenTree(tree).filter((node) => !blocked.has(node.id))

  async function onSubmit(event) {
    event.preventDefault()
    const name = form.name.trim()
    if (!name) return setError('Vui lòng nhập tên danh mục.')
    if (!/^\d+$/.test(form.sortOrder)) return setError('Thứ tự là số nguyên ≥ 0.')
    setSaving(true)
    setError('')
    const body = {
      name,
      parentId: form.parentId || null,
      sortOrder: Number(form.sortOrder),
      active: form.active,
    }
    try {
      const saved = editing ? await updateCategory(category.id, body) : await createCategory(body)
      onSaved(saved, editing)
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }

  return (
    <Modal title={editing ? 'Sửa danh mục' : 'Thêm danh mục'} onClose={onClose}>
      <form className="admin-catalog__form" noValidate onSubmit={onSubmit}>
        <label>
          <span>Tên danh mục *</span>
          <input
            data-autofocus
            value={form.name}
            maxLength={NAME_MAX}
            onChange={(event) => setForm({ ...form, name: event.target.value })}
          />
        </label>
        <label>
          <span>Danh mục cha</span>
          <select
            value={form.parentId}
            onChange={(event) => setForm({ ...form, parentId: event.target.value })}
          >
            <option value="">— Danh mục cấp 1 —</option>
            {parents.map((node) => (
              <option key={node.id} value={node.id}>
                {`${'— '.repeat(node.depth)}${node.name}`}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>Thứ tự hiển thị</span>
          <input
            inputMode="numeric"
            value={form.sortOrder}
            onChange={(event) =>
              setForm({ ...form, sortOrder: event.target.value.replace(/[^\d]/g, '') })
            }
          />
        </label>
        <label className="admin-catalog__checkbox">
          <input
            type="checkbox"
            checked={form.active}
            onChange={(event) => setForm({ ...form, active: event.target.checked })}
          />
          <span>Hiển thị cho người mua</span>
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
          <button type="submit" className="account-button" disabled={saving}>
            {saving ? 'Đang lưu…' : 'Lưu danh mục'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

export default function AdminCategoriesPage() {
  const [state, setState] = useState({ status: 'loading', tree: [], error: '' })
  const [reload, setReload] = useState(0)
  const [dialog, setDialog] = useState(null)
  const [confirm, setConfirm] = useState(null)
  const [notice, setNotice] = useState('')
  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState('')

  useEffect(() => {
    let current = true
    listAdminCategories()
      .then((tree) => current && setState({ status: 'ready', tree: sortTree(tree), error: '' }))
      .catch(
        (failure) =>
          current && setState({ status: 'error', tree: [], error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [reload])

  async function run(id, action, message) {
    if (busy) return
    setBusy(id)
    setActionError('')
    setNotice('')
    try {
      await action()
      setNotice(message)
      setReload((value) => value + 1)
    } catch (failure) {
      const apiError = getApiError(failure)
      setActionError(
        apiError.status === 409
          ? `Không xoá được: ${apiError.message} Hãy chuyển danh mục con và sản phẩm sang danh mục khác, hoặc ẩn danh mục thay vì xoá.`
          : apiError.message,
      )
    } finally {
      setBusy('')
    }
  }

  const rows = flattenTree(state.tree)

  return (
    <section className="admin-catalog" aria-labelledby="admin-categories-heading">
      <div className="admin-users__header">
        <div>
          <span className="account-eyebrow">Danh mục sản phẩm</span>
          <h1 id="admin-categories-heading">Quản lý danh mục</h1>
          <p className="account-subtitle">
            Danh mục nhiều cấp dùng cho thanh danh mục, bộ lọc và form đăng sản phẩm.
          </p>
        </div>
        <button type="button" className="account-button" onClick={() => setDialog({})}>
          + Thêm danh mục
        </button>
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
      {state.status === 'loading' && (
        <p className="account-notice" role="status">
          Đang tải danh mục…
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
        <p className="admin-users__empty">Chưa có danh mục nào.</p>
      )}
      {rows.length > 0 && (
        <div className="admin-users__table-wrap">
          <table className="admin-table admin-catalog__tree">
            <caption className="visually-hidden">Cây danh mục</caption>
            <thead>
              <tr>
                <th scope="col">Danh mục</th>
                <th scope="col">Thứ tự</th>
                <th scope="col">Trạng thái</th>
                <th scope="col">
                  <span className="visually-hidden">Thao tác</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((node) => (
                <tr key={node.id} className={node.active ? '' : 'is-inactive'}>
                  <td>
                    <span
                      className="admin-catalog__name"
                      style={{ paddingInlineStart: `${node.depth * 24}px` }}
                    >
                      {node.depth > 0 && <span aria-hidden="true">└ </span>}
                      {node.name}
                      {node.children?.length > 0 && (
                        <small> · {node.children.length} danh mục con</small>
                      )}
                    </span>
                  </td>
                  <td>{node.sortOrder ?? 0}</td>
                  <td>
                    <span
                      className={`product-status product-status--${node.active ? 'active' : 'hidden'}`}
                    >
                      {node.active ? 'Đang hiện' : 'Đang ẩn'}
                    </span>
                  </td>
                  <td>
                    <div className="admin-catalog__actions">
                      <button
                        type="button"
                        className="link-button"
                        onClick={() => setDialog({ parentId: node.id })}
                      >
                        Thêm con
                      </button>
                      <button
                        type="button"
                        className="link-button"
                        onClick={() => setDialog({ category: node })}
                      >
                        Sửa
                      </button>
                      <button
                        type="button"
                        className="link-button"
                        disabled={busy === node.id}
                        onClick={() =>
                          run(
                            node.id,
                            () => setCategoryVisibility(node.id, !node.active),
                            node.active ? `Đã ẩn “${node.name}”.` : `Đã hiện “${node.name}”.`,
                          )
                        }
                      >
                        {node.active ? 'Ẩn' : 'Hiện'}
                      </button>
                      <button
                        type="button"
                        className="link-button admin-catalog__danger"
                        disabled={busy === node.id}
                        onClick={() => setConfirm(node)}
                      >
                        Xoá
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {dialog && (
        <CategoryDialog
          tree={state.tree}
          category={dialog.category}
          parentId={dialog.parentId}
          onClose={() => setDialog(null)}
          onSaved={(saved, editing) => {
            setDialog(null)
            setNotice(editing ? `Đã cập nhật “${saved.name}”.` : `Đã thêm “${saved.name}”.`)
            setReload((value) => value + 1)
          }}
        />
      )}
      {confirm && (
        <Modal
          title="Xoá danh mục?"
          description={`Chỉ xoá được “${confirm.name}” khi không còn danh mục con và sản phẩm.`}
          onClose={() => setConfirm(null)}
        >
          <div className="account-actions">
            <button
              type="button"
              className="account-button account-button--secondary"
              data-autofocus
              onClick={() => setConfirm(null)}
            >
              Giữ lại
            </button>
            <button
              type="button"
              className="account-button account-button--danger"
              onClick={() => {
                const node = confirm
                setConfirm(null)
                run(node.id, () => deleteCategory(node.id), `Đã xoá “${node.name}”.`)
              }}
            >
              Xoá danh mục
            </button>
          </div>
        </Modal>
      )}
    </section>
  )
}
