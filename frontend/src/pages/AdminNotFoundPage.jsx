import { Link } from 'react-router-dom'
import '../components/adminLayout.css'

// Unknown /admin/... address: stays inside the admin layout, so it must not add a second <main>.
export default function AdminNotFoundPage() {
  return (
    <section className="admin-placeholder" aria-labelledby="admin-not-found-title">
      <h1 id="admin-not-found-title">Không tìm thấy trang</h1>
      <p className="admin-placeholder__note">
        Địa chỉ này không thuộc khu vực quản trị. <Link to="/admin">Về trang tổng quan</Link>
      </p>
    </section>
  )
}
