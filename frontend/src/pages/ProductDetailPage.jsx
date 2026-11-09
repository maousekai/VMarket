import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import ProductGrid from '../components/ProductGrid'
import SectionError from '../components/SectionError'
import StarIcon from '../components/StarIcon'
import { useCategoryTree } from '../hooks/useCatalog'
import useRemoteData from '../hooks/useRemoteData'
import { getApiError } from '../services/api/apiClient'
import { getProduct } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'
import { findPath } from '../utils/category'
import { formatMoney } from '../utils/formatMoney'
import { imageUrlOrEmpty } from '../utils/shop'
import { attributeGroups, findVariant, initialSelection, isOptionAvailable } from '../utils/variant'
import '../components/home.css'
import '../components/catalog.css'
import '../components/productDetail.css'

const ratingFormat = new Intl.NumberFormat('vi-VN', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})
const countFormat = new Intl.NumberFormat('vi-VN')

function Gallery({ images, name }) {
  const [active, setActive] = useState(0)
  const [failed, setFailed] = useState({})
  const current = images[active]
  return (
    <div className="pdp-gallery">
      <div className="pdp-gallery__main">
        {current && !failed[current] ? (
          <img src={current} alt={name} onError={() => setFailed({ ...failed, [current]: true })} />
        ) : (
          <span className="product-card__placeholder" aria-hidden="true" />
        )}
      </div>
      {images.length > 1 && (
        <div className="pdp-gallery__thumbs" role="group" aria-label="Ảnh sản phẩm">
          {images.map((src, index) => (
            <button
              key={src}
              type="button"
              aria-label={`Xem ảnh ${index + 1}`}
              aria-pressed={index === active}
              onClick={() => setActive(index)}
            >
              <img src={src} alt="" loading="lazy" />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function ShopCard({ shopId }) {
  const load = useCallback(() => getPublicShop(shopId), [shopId])
  const shop = useRemoteData(load, `shop-${shopId}`)
  if (shop.status !== 'ready' || !shop.data) return null
  const logo = imageUrlOrEmpty(shop.data.logoUrl)
  return (
    <section className="pdp-card pdp-shop" aria-label="Gian hàng">
      <span className="pdp-shop__logo" aria-hidden="true">
        {logo ? <img src={logo} alt="" /> : shop.data.name?.charAt(0).toLocaleUpperCase('vi')}
      </span>
      <div className="pdp-shop__text">
        <strong>{shop.data.name}</strong>
        {shop.data.province && <span>{shop.data.province}</span>}
      </div>
      <Link className="button button--secondary" to={`/shops/${encodeURIComponent(shopId)}`}>
        Xem gian hàng
      </Link>
    </section>
  )
}

/**
 * Khung mua hang: chon bien the + so luong. `renderActions` cho phep trang gan nut
 * them vao gio (Cart UI, PBL6-29) ma khong tron logic gio hang vao day.
 */
export function PurchasePanel({ product, renderActions }) {
  const variants = product.variants || []
  const groups = attributeGroups(variants)
  const [selection, setSelection] = useState(() => initialSelection(variants))
  const [quantity, setQuantity] = useState(1)
  const variant = findVariant(variants, selection)
  const stock = variant ? (variant.availableStock ?? 0) : null
  const maxQuantity = Math.max(1, Math.min(stock ?? 1, 999))
  const qty = Math.min(quantity, maxQuantity)
  const ranged = product.maxPrice != null && product.maxPrice > product.minPrice

  return (
    <div className="pdp-purchase">
      <div className="pdp-price">
        <strong>
          {variant
            ? formatMoney(variant.price, product.currency)
            : `${formatMoney(product.minPrice, product.currency)}${
                ranged ? ` – ${formatMoney(product.maxPrice, product.currency)}` : ''
              }`}
        </strong>
        <span>Giá đã bao gồm VAT · Thanh toán khi nhận hàng hoặc PayOS</span>
      </div>

      {groups.map((group) => (
        <fieldset className="pdp-option" key={group.name}>
          <legend>
            {group.name}: <strong>{selection[group.name] || 'Chưa chọn'}</strong>
          </legend>
          <div className="pdp-option__values">
            {group.values.map((value) => {
              const available = isOptionAvailable(variants, selection, group.name, value)
              return (
                <button
                  key={value}
                  type="button"
                  aria-pressed={selection[group.name] === value}
                  className={available ? '' : 'is-unavailable'}
                  onClick={() => setSelection({ ...selection, [group.name]: value })}
                >
                  {value}
                </button>
              )
            })}
          </div>
        </fieldset>
      ))}

      <div className="pdp-quantity">
        <span id="pdp-quantity-label">Số lượng</span>
        <div className="quantity-stepper" role="group" aria-labelledby="pdp-quantity-label">
          <button
            type="button"
            aria-label="Giảm số lượng"
            disabled={qty <= 1}
            onClick={() => setQuantity(qty - 1)}
          >
            −
          </button>
          <input
            aria-label="Số lượng"
            inputMode="numeric"
            value={qty}
            onChange={(event) => {
              const next = Number.parseInt(event.target.value.replace(/\D/g, ''), 10)
              setQuantity(Number.isFinite(next) && next > 0 ? next : 1)
            }}
          />
          <button
            type="button"
            aria-label="Tăng số lượng"
            disabled={qty >= maxQuantity}
            onClick={() => setQuantity(qty + 1)}
          >
            +
          </button>
        </div>
        <span className={`pdp-stock${stock === 0 ? ' is-out' : ''}`} role="status">
          {variant
            ? stock > 0
              ? `Còn ${countFormat.format(stock)} sản phẩm`
              : 'Phân loại này đã hết hàng'
            : 'Chọn đủ phân loại để xem tồn kho'}
        </span>
      </div>
      {renderActions?.({ variant, quantity: qty, soldOut: stock === 0 })}
    </div>
  )
}

function Specs({ product, categoryPath }) {
  const rows = [
    ['Danh mục', categoryPath.map((node) => node.name).join(' › ')],
    ['Đã bán', countFormat.format(product.soldCount ?? 0)],
    ['Số phân loại', countFormat.format(product.variants?.length ?? 0)],
  ].filter(([, value]) => value)
  return (
    <dl className="pdp-specs">
      {rows.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

export function ProductDetailView({ detail, categoryTree, renderActions }) {
  const { product, similarProducts = [] } = detail
  const categoryPath = findPath(categoryTree, product.categoryId)
  const images = (product.imageUrls || []).map(imageUrlOrEmpty).filter(Boolean)
  return (
    <>
      <nav className="catalog-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        {categoryPath.map((node) => (
          <span key={node.id}>
            <span aria-hidden="true">›</span>
            <Link to={`/search?categoryId=${encodeURIComponent(node.id)}`}>{node.name}</Link>
          </span>
        ))}
        <span>
          <span aria-hidden="true">›</span>
          <span aria-current="page">{product.name}</span>
        </span>
      </nav>

      <section className="pdp-card pdp-hero">
        <Gallery images={images} name={product.name} />
        <div className="pdp-info">
          <h1>{product.name}</h1>
          <p className="pdp-meta">
            {product.ratingCount > 0 ? (
              <span className="pdp-meta__rating">
                <StarIcon size={14} />
                {ratingFormat.format(product.ratingAverage)}
                <span>({countFormat.format(product.ratingCount)} đánh giá)</span>
              </span>
            ) : (
              <span>Chưa có đánh giá</span>
            )}
            <span aria-hidden="true">·</span>
            <span>{countFormat.format(product.soldCount ?? 0)} đã bán</span>
          </p>
          <PurchasePanel key={product.id} product={product} renderActions={renderActions} />
        </div>
      </section>

      <ShopCard shopId={product.shopId} />

      <section className="pdp-card pdp-details" aria-labelledby="pdp-description">
        <div>
          <h2 id="pdp-description">Mô tả sản phẩm</h2>
          <p className="pdp-description">{product.description || 'Người bán chưa thêm mô tả.'}</p>
        </div>
        <div>
          <h2>Thông tin sản phẩm</h2>
          <Specs product={product} categoryPath={categoryPath} />
        </div>
      </section>

      {similarProducts.length > 0 && (
        <section className="home-section" aria-labelledby="pdp-similar">
          <div className="section-header">
            <div className="section-header__title">
              <h2 id="pdp-similar">Sản phẩm tương tự</h2>
            </div>
          </div>
          <ProductGrid status="ready" products={similarProducts} />
        </section>
      )}
    </>
  )
}

export default function ProductDetailPage({ renderActions }) {
  const { productId } = useParams()
  const load = useCallback(() => getProduct(productId), [productId])
  const detail = useRemoteData(load, productId)
  const tree = useCategoryTree()

  let body
  if (detail.status === 'loading') {
    body = (
      <p className="section-state" role="status">
        Đang tải sản phẩm…
      </p>
    )
  } else if (detail.status === 'error') {
    body =
      getApiError(detail.error).status === 404 ? (
        <section className="pdp-card pdp-missing">
          <h1>Sản phẩm không còn được bán</h1>
          <p>Sản phẩm có thể đã bị ẩn, bị gỡ hoặc gian hàng tạm ngừng hoạt động.</p>
          <Link className="button button--primary" to="/search">
            Xem sản phẩm khác
          </Link>
        </section>
      ) : (
        <SectionError what="sản phẩm" error={detail.error} onRetry={detail.reload} />
      )
  } else {
    body = (
      <ProductDetailView
        detail={detail.data}
        categoryTree={tree.data || []}
        renderActions={renderActions}
      />
    )
  }
  return <main className="home catalog-page pdp">{body}</main>
}
