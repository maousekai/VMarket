import { Link } from 'react-router-dom'
import { ACTIVE_CITY } from '../data/city'
import brandBasket from '../assets/account/brand-basket.svg'
import checkCircle from '../assets/home/check-circle.svg'
import './siteFooter.css'

// Chi liet ke nhung cam ket da co trong SRS (FR-SHIP-05, FR-ORD-06, FR-PAY-01/03).
const promises = [
  `Giao hàng nội thành ${ACTIVE_CITY.name}`,
  'Theo dõi đơn hàng thời gian thực',
  'Đổi trả trong 7 ngày',
  'Thanh toán PayOS hoặc khi nhận hàng',
]

const payments = [
  { label: 'PayOS', tone: 'payos' },
  { label: 'VietQR', tone: 'vietqr' },
  { label: 'COD', tone: 'cod' },
]

export default function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="site-footer__inner">
        <div className="site-footer__brand">
          <Link className="site-footer__logo" to="/" aria-label="VMarket — Trang chủ">
            <span className="site-footer__logo-icon">
              <img src={brandBasket} alt="" />
            </span>
            VMarket
          </Link>
          <p>
            Chợ trực tuyến đa nhà bán tại TP. {ACTIVE_CITY.name}. Kết nối người mua và nhà bán tại{' '}
            {ACTIVE_CITY.districts.length} quận nội thành.
          </p>
          <h3 className="site-footer__label">Phương thức thanh toán</h3>
          <ul className="site-footer__payments">
            {payments.map(({ label, tone }) => (
              <li key={label} className={`site-footer__payment site-footer__payment--${tone}`}>
                {label}
              </li>
            ))}
          </ul>
        </div>
        <nav className="site-footer__column" aria-label="VMarket">
          <h3>VMarket</h3>
          <ul>
            <li>
              <Link to="/about">Về chúng tôi</Link>
            </li>
            <li>
              <Link to="/seller/register">Trở thành nhà bán</Link>
            </li>
            <li>
              <Link to="/register">Tạo tài khoản</Link>
            </li>
          </ul>
        </nav>
        <div className="site-footer__column">
          <h3>Mua sắm an tâm</h3>
          <ul className="site-footer__promises">
            {promises.map((text) => (
              <li key={text}>
                <img src={checkCircle} alt="" width="12" height="12" />
                {text}
              </li>
            ))}
          </ul>
        </div>
        <div className="site-footer__column">
          <h3>Khu vực phục vụ</h3>
          <ul className="site-footer__districts">
            {ACTIVE_CITY.districts.map((district) => (
              <li key={district}>{district}</li>
            ))}
          </ul>
        </div>
      </div>
      <div className="site-footer__bottom">
        <p>
          © {new Date().getFullYear()} VMarket. Sàn thương mại điện tử đa nhà bán tại TP.{' '}
          {ACTIVE_CITY.name}, Việt Nam.
        </p>
      </div>
    </footer>
  )
}
