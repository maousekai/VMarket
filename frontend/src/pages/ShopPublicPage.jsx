import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { listProducts } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'
import { formatDate } from '../utils/formatDate'
import { formatCurrency, imageUrlOrEmpty } from '../utils/shop'
import '../components/account.css'
import '../components/shopPublic.css'

const PAGE_SIZE = 12

const initialProducts = {
  status: 'loading',
  rows: [],
  total: 0,
  page: 0,
  totalPages: 0,
  loadingMore: false,
  loadMoreError: null,
}

function productPageState(page) {
  const rows = Array.isArray(page?.content) ? page.content : []
  const total = page?.totalElements ?? rows.length
  const totalPages = page?.totalPages ?? (total > 0 ? Math.ceil(total / PAGE_SIZE) : 0)
  return {
    status: 'ready',
    rows,
    total,
    page: page?.page ?? 0,
    totalPages,
    loadingMore: false,
    loadMoreError: null,
  }
}

function ShopLogo({ shop }) {
  const logo = imageUrlOrEmpty(shop.logoUrl)
  const [failed, setFailed] = useState(false)

  useEffect(() => setFailed(false), [logo])

  if (logo && !failed) {
    return <img src={logo} alt={`Logo ${shop.name}`} onError={() => setFailed(true)} />
  }
  return <span aria-hidden="true">{shop.name.trim().charAt(0).toLocaleUpperCase('vi')}</span>
}

function ShopCover({ shop }) {
  const cover = imageUrlOrEmpty(shop.coverUrl)
  const [failed, setFailed] = useState(false)

  useEffect(() => setFailed(false), [cover])

  const showImage = Boolean(cover) && !failed
  return (
    <div className={`shop-hero__cover${showImage ? ' has-image' : ''}`}>
      {showImage ? (
        <img src={cover} alt="" onError={() => setFailed(true)} />
      ) : (
        <span className="shop-hero__cover-mark" aria-hidden="true">
          <small>VMarket</small>
          <strong>{shop.name.trim().charAt(0).toLocaleUpperCase('vi')}</strong>
        </span>
      )}
    </div>
  )
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
  const activeShopRef = useRef(shopId)
  const [shopState, setShopState] = useState({ status: 'loading', shop: null, error: null })
  const [products, setProducts] = useState(initialProducts)

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
    activeShopRef.current = shopId
    setProducts(initialProducts)
    listProducts({ shopId, page: 0, size: PAGE_SIZE })
      .then((page) => {
        if (current) setProducts(productPageState(page))
      })
      .catch(() => {
        if (current) {
          setProducts({
            ...initialProducts,
            status: 'unavailable',
          })
        }
      })
    return () => {
      current = false
      if (activeShopRef.current === shopId) activeShopRef.current = null
    }
  }, [shopId])

  async function loadMore() {
    if (products.loadingMore || products.page + 1 >= products.totalPages) return

    const nextPage = products.page + 1
    const requestedShop = shopId
    setProducts((current) => ({ ...current, loadingMore: true, loadMoreError: null }))
    try {
      const page = await listProducts({ shopId: requestedShop, page: nextPage, size: PAGE_SIZE })
      if (activeShopRef.current !== requestedShop) return
      setProducts((current) => {
        if (activeShopRef.current !== requestedShop) return current
        const next = productPageState(page)
        const seen = new Set(current.rows.map((product) => product.id))
        return {
          ...next,
          rows: [...current.rows, ...next.rows.filter((product) => !seen.has(product.id))],
        }
      })
    } catch {
      if (activeShopRef.current !== requestedShop) return
      setProducts((current) => ({
        ...current,
        loadingMore: false,
        loadMoreError: 'Không tải thêm được sản phẩm. Vui lòng thử lại.',
      }))
    }
  }

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
  const hasMore = products.status === 'ready' && products.page + 1 < products.totalPages

  return (
    <main className="shop-public">
      <nav className="account-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        <span aria-hidden="true">›</span>
        <span>{shop.name}</span>
      </nav>

      <section className="shop-hero" aria-labelledby="shop-public-name">
        <ShopCover shop={shop} />
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
              <li>{products.status === 'ready' ? `${products.total} sản phẩm` : '—'}</li>
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
                    <h3>
                      {/* The link stretches over the whole card so the card is one click target. */}
                      <Link
                        className="shop-product-card__link"
                        to={`/products/${encodeURIComponent(product.id)}`}
                      >
                        {product.name}
                      </Link>
                    </h3>
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
          {(hasMore || products.loadingMore || products.loadMoreError) && (
            <div className="shop-public__more">
              {products.loadMoreError && (
                <p className="account-notice account-notice--warning" role="alert">
                  {products.loadMoreError}
                </p>
              )}
              {hasMore && (
                <button
                  className="account-button account-button--secondary shop-public__more-button"
                  type="button"
                  disabled={products.loadingMore}
                  onClick={loadMore}
                >
                  {products.loadingMore ? 'Đang tải…' : 'Tải thêm sản phẩm'}
                </button>
              )}
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
