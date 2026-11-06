import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import SectionError from './SectionError'
import SectionHeader from './SectionHeader'
import chevronLeft from '../assets/home/chevron-left.svg'

function ShopCover({ src }) {
  const [failed, setFailed] = useState(false)
  return (
    <div className="shop-card__cover">
      {src && !failed && (
        <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />
      )}
    </div>
  )
}

function ShopCard({ shop }) {
  const since = shop.approvedAt ? new Date(shop.approvedAt).getUTCFullYear() : null
  return (
    <article className="shop-card">
      <ShopCover
        key={shop.coverUrl || shop.logoUrl || 'none'}
        src={shop.coverUrl || shop.logoUrl}
      />
      <div className="shop-card__body">
        <h3 className="shop-card__name">{shop.name}</h3>
        <p className="shop-card__meta">
          {[shop.province, since && `Trên VMarket từ ${since}`].filter(Boolean).join(' · ')}
        </p>
        {shop.description && <p className="shop-card__description">{shop.description}</p>}
        <Link
          className="button button--secondary shop-card__action"
          to={`/shops/${encodeURIComponent(shop.id)}`}
          aria-label={`Xem gian hàng ${shop.name}`}
        >
          Xem gian hàng
        </Link>
      </div>
    </article>
  )
}

export default function FeaturedShops({ status, shops, error, onRetry, title }) {
  const listRef = useRef(null)
  const scroll = (direction) => {
    const list = listRef.current
    list?.scrollBy({ left: direction * list.clientWidth * 0.8, behavior: 'smooth' })
  }

  // Nothing worth a section yet (e.g. a fresh install with no approved shops).
  if (status === 'ready' && !shops?.length) return null

  return (
    <section className="home-section" aria-labelledby="featured-shops-title">
      <SectionHeader id="featured-shops-title" title={title}>
        {status === 'ready' && shops.length > 1 && (
          <div className="carousel-buttons">
            <button type="button" aria-label="Gian hàng trước" onClick={() => scroll(-1)}>
              <img src={chevronLeft} alt="" width="14" height="14" />
            </button>
            <button
              type="button"
              aria-label="Gian hàng tiếp theo"
              className="carousel-buttons__next"
              onClick={() => scroll(1)}
            >
              <img src={chevronLeft} alt="" width="14" height="14" />
            </button>
          </div>
        )}
      </SectionHeader>
      {status === 'error' ? (
        <SectionError what="gian hàng" error={error} onRetry={onRetry} />
      ) : status === 'loading' ? (
        <div className="shop-list" role="status" aria-label="Đang tải gian hàng">
          {Array.from({ length: 4 }, (_, index) => (
            <div className="shop-card shop-card--skeleton" key={index} aria-hidden="true">
              <div className="shop-card__cover" />
              <div className="shop-card__body">
                <span className="skeleton-line" />
                <span className="skeleton-line skeleton-line--short" />
              </div>
            </div>
          ))}
        </div>
      ) : (
        <ul className="shop-list" ref={listRef}>
          {shops.map((shop) => (
            <li key={shop.id}>
              <ShopCard shop={shop} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
