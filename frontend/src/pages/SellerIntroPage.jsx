import { Link } from 'react-router-dom'
import sellerBag from '../assets/account/seller-bag.svg'
import '../components/account.css'

export default function SellerIntroPage() {
  return (
    <main className="account-page account-page--narrow">
      <nav className="account-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        <span aria-hidden="true">›</span>
        <span>Kênh người bán</span>
      </nav>
      <div className="seller-intro-banner">
        <span className="seller-intro-banner__icon">
          <img src={sellerBag} alt="" />
        </span>
        <div>
          <strong>Bắt đầu kinh doanh trên VMarket Đà Nẵng</strong>
          <p>Kết nối với người mua tại Đà Nẵng. Quản lý sản phẩm và đơn hàng trên VMarket.</p>
        </div>
      </div>
      <section className="account-card account-state seller-intro">
        <h1>Đưa gian hàng của bạn đến VMarket</h1>
        <p>Kênh người bán là nơi bạn quản lý gian hàng sau khi hồ sơ được xét duyệt.</p>
        <ol className="seller-steps">
          <li>
            <span>1</span>
            <div>
              <strong>Chuẩn bị thông tin gian hàng</strong>
              <p>Tên gian hàng, thông tin liên hệ và địa chỉ lấy hàng.</p>
            </div>
          </li>
          <li>
            <span>2</span>
            <div>
              <strong>Chờ xét duyệt hồ sơ</strong>
              <p>Theo dõi kết quả duyệt và lý do cần bổ sung.</p>
            </div>
          </li>
          <li>
            <span>3</span>
            <div>
              <strong>Bắt đầu kinh doanh</strong>
              <p>Gian hàng được duyệt có thể vào khu vực người bán.</p>
            </div>
          </li>
        </ol>
        <p className="account-notice" role="status">
          Tính năng gửi hồ sơ mở gian hàng sẽ được bổ sung ở bước tiếp theo.
        </p>
        <div className="account-actions">
          <Link className="account-button" to="/profile">
            Kiểm tra thông tin liên hệ
          </Link>
          <Link className="account-button account-button--secondary" to="/">
            Về trang mua sắm
          </Link>
        </div>
      </section>
    </main>
  )
}
