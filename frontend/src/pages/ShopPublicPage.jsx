import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { listProducts } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'
import { formatDate } from '../utils/formatDate'
import { formatCurrency, imageUrlOrEmpty } from '../utils/shop'
import '../components/account.css'
import '../components/shopPublic.css'

const PAGE_SIZE = 12

function ShopLogo({ shop }) {
  const logo = imageUrlOrEmpty(shop.logoUrl)
  if (logo) return <img src={logo} alt={`Logo ${shop.name}`} />
  return <span aria-hidden="true">{shop.name.trim().charAt(0).toLocaleUpperCase('vi')}</span>
}

function ProductImage({ product }) {
  const [failed, setFailed] = useState(false)
  const image = imageUrlOrEmpty(product.imageUrls?.[0])
  if (!image || failed) {
    return (
      <span className="shop-product-card__placeholder" aria-hidden="true">
        ◫
      </span>
    )
  }
  return <img src={image} alt="" loading="lazy" onError={() => setFailed(true)} />
}

function priceLabel(product) {
  if (product.minPrice == null && product.maxPrice == null) return 'Liên hệ'
  if (product.minPrice === product.maxPrice) return formatCurrency(product.minPrice)
  return `${formatCurrency(product.minPrice)} – ${formatCurrency(product.maxPrice)}`
}

export default function ShopPublicPage() {
  const { shopId } = useParams()
  const [shopState, setShopState] = useState({ status: 'loading', shop: null, error: null })
  const [products, setProducts] = useState({ status: 'loading', rows: [], total: 0 })

  useEffect(() => {
    let current = true
    setShopState({ status: 'loading', shop: null, error: null })
    getPublicShop(shopId)
      .then((shop) => {
        if (current) setShopState({ status: 'ready', shop, error: null })
      })
      .catch((failure) => {
        if (current) setShopState({ status: 'error', shop: null, error: getApiError(failure) })
      })
    return () => {
      current = false
    }
  }, [shopId])

  useEffect(() => {
    let current = true
    setProducts({ status: 'loading', rows: [], total: 0 })
    listProducts({ shopId, page: 0, size: PAGE_SIZE })
      .then((page) => {
        if (current)
          setProducts({ status: 'ready', rows: page.items || [], total: page.totalElements || 0 })
      })
      .catch(() => {
        if (current) setProducts({ status: 'unavailable', rows: [], total: 0 })
      })
    return () => {
      current = false
    }
  }, [shopId])

  if (shopState.status === 'loading') {
    return (
      <main className="shop-public">
        <p className="account-notice" role="status">
          Đang mở gian hàng…
        </p>
      </main>
    )
  }

  if (shopState.status === 'error') {
    return (
      <main className="shop-public shop-public--empty">
        <section className="account-card account-state">
          <h1>Không tìm thấy gian hàng</h1>
          <p>
            {shopState.error?.status === 404
              ? 'Gian hàng không tồn tại hoặc hiện không hoạt động trên VMarket.'
              : shopState.error?.message}
          </p>
          <Link className="account-button" to="/">
            Về trang chủ
          </Link>
        </section>
      </main>
    )
  }

  const shop = shopState.shop
  const cover = imageUrlOrEmpty(shop.coverUrl)

  return (
    <main className="shop-public">
      <nav className="account-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        <span aria-hidden="true">›</span>
        <span>{shop.name}</span>
      </nav>

      <section className="shop-hero" aria-labelledby="shop-public-name">
        <div className={`shop-hero__cover${cover ? ' has-image' : ''}`}>
          {cover && <img src={cover} alt="" />}
        </div>
        <div className="shop-hero__body">
          <div className="shop-hero__logo">
            <ShopLogo shop={shop} />
          </div>
          <div className="shop-hero__identity">
            <span className="shop-public__verified">Gian hàng chính thức</span>
            <h1 id="shop-public-name">{shop.name}</h1>
            <p>{shop.description || 'Gian hàng đang cập nhật giới thiệu.'}</p>
            <ul className="shop-hero__meta">
              <li>{shop.province}</li>
              {shop.approvedAt && <li>Hoạt động từ {formatDate(shop.approvedAt)}</li>}
              <li>
                {products.total > 0 ? `${products.total} sản phẩm` : 'Đang cập nhật sản phẩm'}
              </li>
            </ul>
          </div>
        </div>
      </section>

      <div className="shop-public__layout">
        <section className="shop-public__products" aria-labelledby="shop-products-heading">
          <div className="shop-public__section-head">
            <div>
              <h2 id="shop-products-heading">Sản phẩm của gian hàng</h2>
              <p>Danh sách sản phẩm đang được bán.</p>
            </div>
            {products.status === 'ready' && <span>{products.total} sản phẩm</span>}
          </div>

          {products.status === 'loading' && (
            <div className="shop-product-grid" aria-busy="true">
              {Array.from({ length: 4 }, (_, index) => (
                <div key={index} className="shop-product-card shop-product-card--skeleton" />
              ))}
            </div>
          )}
          {products.status === 'unavailable' && (
            <p className="account-notice account-notice--warning">
              Danh mục sản phẩm tạm thời chưa tải được. Vui lòng quay lại sau.
            </p>
          )}
          {products.status === 'ready' && products.rows.length === 0 && (
            <div className="account-card shop-public__empty">
              <strong>Gian hàng chưa có sản phẩm</strong>
              <p>Người bán đang chuẩn bị hàng mới. Hãy quay lại sau nhé.</p>
            </div>
          )}
          {products.rows.length > 0 && (
            <div className="shop-product-grid">
              {products.rows.map((product) => (
                <article className="shop-product-card" key={product.id}>
                  <div className="shop-product-card__image">
                    <ProductImage product={product} />
                  </div>
                  <div className="shop-product-card__body">
                    <h3>{product.name}</h3>
                    <strong>{priceLabel(product)}</strong>
                    <p>
                      {product.soldCount > 0 ? `Đã bán ${product.soldCount}` : 'Sản phẩm mới'}
                      {product.availableStock != null && (
                        <span> · Còn {product.availableStock}</span>
                      )}
                    </p>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>

        <aside className="shop-public__aside">
          <section className="account-card shop-info-card">
            <h2>Thông tin gian hàng</h2>
            <dl>
              <div>
                <dt>Khu vực</dt>
                <dd>{shop.province}</dd>
              </div>
              {shop.approvedAt && (
                <div>
                  <dt>Tham gia</dt>
                  <dd>{formatDate(shop.approvedAt)}</dd>
                </div>
              )}
              <div>
                <dt>Đang bán</dt>
                <dd>{products.status === 'ready' ? `${products.total} sản phẩm` : '—'}</dd>
              </div>
            </dl>
          </section>
          <section className="account-card shop-info-card">
            <h2>Chính sách của gian hàng</h2>
            <p className="shop-info-card__policy">
              {shop.policies || 'Gian hàng chưa công bố chính sách đổi trả / vận chuyển.'}
            </p>
          </section>
        </aside>
      </div>
    </main>
  )
}
