import { useState } from 'react'
import { Link } from 'react-router-dom'
import { formatMoney } from '../utils/formatMoney'
import StarIcon from './StarIcon'

const ratingFormat = new Intl.NumberFormat('vi-VN', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

function ProductImage({ src }) {
  const [failed, setFailed] = useState(false)
  if (!src || failed) return <span className="product-card__placeholder" aria-hidden="true" />
  return <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />
}

export default function ProductCard({ product }) {
  const { id, name, imageUrls, minPrice, maxPrice, currency, ratingAverage, ratingCount } = product
  const ranged = maxPrice != null && minPrice != null && maxPrice > minPrice
  const price = formatMoney(minPrice, currency)
  return (
    <article className="product-card">
      <div className="product-card__media">
        <ProductImage key={imageUrls?.[0] || 'none'} src={imageUrls?.[0]} />
      </div>
      <div className="product-card__body">
        <h3 className="product-card__name">
          {/* The link stretches over the whole card so the card is one click target. */}
          <Link to={`/products/${encodeURIComponent(id)}`}>{name}</Link>
        </h3>
        <p className="product-card__rating">
          {ratingCount > 0 ? (
            <>
              <span className="product-card__stars">
                <StarIcon />
                {ratingFormat.format(ratingAverage)}
                <span className="visually-hidden"> trên 5 sao</span>
              </span>
              <span className="product-card__count">({ratingCount} đánh giá)</span>
            </>
          ) : (
            <span className="product-card__count">Chưa có đánh giá</span>
          )}
        </p>
        {price && (
          <p className="product-card__price">
            {ranged && <span className="product-card__from">Từ </span>}
            {price}
          </p>
        )}
      </div>
    </article>
  )
}
