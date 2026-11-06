import ProductCard from './ProductCard'
import SectionError from './SectionError'

export default function ProductGrid({
  status,
  products,
  error,
  onRetry,
  skeletonCount = 8,
  emptyMessage = 'Chưa có sản phẩm nào.',
}) {
  if (status === 'error') return <SectionError what="sản phẩm" error={error} onRetry={onRetry} />
  if (status === 'loading') {
    return (
      <div className="product-grid" role="status" aria-label="Đang tải sản phẩm">
        {Array.from({ length: skeletonCount }, (_, index) => (
          <div className="product-card product-card--skeleton" key={index} aria-hidden="true">
            <div className="product-card__media" />
            <div className="product-card__body">
              <span className="skeleton-line" />
              <span className="skeleton-line skeleton-line--short" />
            </div>
          </div>
        ))}
      </div>
    )
  }
  if (!products?.length) {
    return <p className="section-state section-state--empty">{emptyMessage}</p>
  }
  return (
    <ul className="product-grid">
      {products.map((product) => (
        <li key={product.id}>
          <ProductCard product={product} />
        </li>
      ))}
    </ul>
  )
}
