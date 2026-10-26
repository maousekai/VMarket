import { Link, NavLink } from 'react-router-dom'
import illustration from '../assets/auth/da-nang.png'
import brand from '../assets/auth/brand.png'
import payment from '../assets/auth/payment.png'
import delivery from '../assets/auth/delivery.png'
import search from '../assets/auth/search.png'

export default function AuthLayout({ title, description, tabs = false, children }) {
  return (
    <main className="auth-layout">
      <aside className="auth-story" aria-label="VMarket Đà Nẵng">
        <Link className="auth-brand" to="/" aria-label="VMarket — Trang chủ">
          <span className="auth-brand__icon">
            <img src={brand} width="24" height="24" alt="" />
          </span>
          <span>VMarket</span>
        </Link>
        <p className="auth-story__tagline">
          Chợ Trực Tuyến Đà Nẵng
          <br />
          <strong>Giao Nhanh Nội Thành</strong>
        </p>
        <img
          className="auth-story__art"
          src={illustration}
          width="455"
          height="315"
          alt="Cầu Rồng Đà Nẵng bên sông Hàn"
        />
        <ul className="auth-features">
          <li>
            <img src={payment} width="16" height="16" alt="" />
            Thanh toán PayOS / COD
          </li>
          <li>
            <img src={delivery} width="16" height="16" alt="" />
            Theo dõi giao hàng nội thành
          </li>
          <li>
            <img src={search} width="16" height="16" alt="" />
            AI tìm kiếm bằng hình ảnh
          </li>
        </ul>
      </aside>
      <section className="auth-content">
        <div className="auth-card">
          <Link className="auth-mobile-brand" to="/">
            VMarket
          </Link>
          <header>
            <h1>{title}</h1>
            <p className="auth-description">{description}</p>
          </header>
          {tabs && (
            <nav className="auth-tabs" aria-label="Xác thực tài khoản">
              <NavLink to="/login">Đăng nhập</NavLink>
              <NavLink to="/register">Đăng ký tài khoản</NavLink>
            </nav>
          )}
          {children}
        </div>
      </section>
    </main>
  )
}
