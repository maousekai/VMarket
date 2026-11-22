import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import Pagination from '../components/Pagination'
import ProductGrid from '../components/ProductGrid'
import StarIcon from '../components/StarIcon'
import { useBestSellers, useCategoryTree, useProductSearch } from '../hooks/useCatalog'
import { PRODUCT_SORT } from '../services/api/productApi'
import { SEARCH_SORT } from '../services/api/searchApi'
import { ACTIVE_CITY } from '../data/city'
import { findPath } from '../utils/category'
import '../components/home.css'
import '../components/catalog.css'
import '../components/search.css'

const BROWSE_SORT_OPTIONS = [
  [PRODUCT_SORT.newest, 'Mới nhất'],
  [PRODUCT_SORT.bestSelling, 'Bán chạy'],
  [PRODUCT_SORT.priceAsc, 'Giá tăng dần'],
  [PRODUCT_SORT.priceDesc, 'Giá giảm dần'],
]
// Tim theo tu khoa (AI Search) them "Liên quan nhất" lam mac dinh.
const KEYWORD_SORT_OPTIONS = [[SEARCH_SORT.relevance, 'Liên quan nhất'], ...BROWSE_SORT_OPTIONS]
const RATINGS = [5, 4, 3]

const sortOptions = (keyword) => (keyword ? KEYWORD_SORT_OPTIONS : BROWSE_SORT_OPTIONS)
const defaultSort = (keyword) => (keyword ? SEARCH_SORT.relevance : PRODUCT_SORT.newest)

const toAmount = (value) => {
  if (value === null || value === undefined || value === '') return undefined
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? Math.floor(number) : undefined
}

function readFilters(params) {
  const page = Number.parseInt(params.get('page') || '1', 10)
  const rating = Number(params.get('rating'))
  const keyword = params.get('q')?.trim() || ''
  const sorts = sortOptions(keyword).map(([value]) => value)
  return {
    keyword,
    categoryId: params.get('categoryId') || '',
    minPrice: toAmount(params.get('minPrice')),
    maxPrice: toAmount(params.get('maxPrice')),
    // AI Search khong loc theo danh gia: chi ap dung khi duyet khong co tu khoa.
    minRating: !keyword && RATINGS.includes(rating) ? rating : undefined,
    sort: sorts.includes(params.get('sort')) ? params.get('sort') : defaultSort(keyword),
    page: Number.isFinite(page) && page > 0 ? page - 1 : 0,
  }
}

function PriceFilter({ filters, onApply }) {
  const [min, setMin] = useState(filters.minPrice ?? '')
  const [max, setMax] = useState(filters.maxPrice ?? '')
  const invalid = min !== '' && max !== '' && Number(min) > Number(max)
  return (
    <form
      className="catalog-filter__price"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        if (!invalid) onApply({ minPrice: toAmount(min), maxPrice: toAmount(max) })
      }}
    >
      <div className="catalog-filter__price-row">
        <label>
          <span>Từ</span>
          <input
            type="number"
            min="0"
            step="1000"
            inputMode="numeric"
            placeholder="0"
            value={min}
            onChange={(event) => setMin(event.target.value)}
          />
        </label>
        <label>
          <span>Đến</span>
          <input
            type="number"
            min="0"
            step="1000"
            inputMode="numeric"
            placeholder="Không giới hạn"
            value={max}
            onChange={(event) => setMax(event.target.value)}
          />
        </label>
      </div>
      {invalid && (
        <p className="field-error" role="alert">
          Giá tối thiểu phải nhỏ hơn giá tối đa.
        </p>
      )}
      <button className="button button--primary catalog-filter__apply" type="submit">
        Áp dụng
      </button>
    </form>
  )
}

/** Khong co ket qua (Figma UC-03 NoResult): giu bo loc de chinh, goi y ban chay. */
function NoResults({ filters, hasFilters, onClearFilters }) {
  const bestSellers = useBestSellers(filters.categoryId, true)
  return (
    <div className="search-empty">
      <div className="search-empty__panel" role="status">
        <h2>Chưa tìm thấy sản phẩm</h2>
        <p>
          {filters.keyword
            ? `Không có sản phẩm khớp với “${filters.keyword}”. Thử từ khóa ngắn hơn hoặc bỏ bớt bộ lọc.`
            : 'Không có sản phẩm khớp với bộ lọc hiện tại.'}
        </p>
        {hasFilters && (
          <button type="button" className="button button--primary" onClick={onClearFilters}>
            Xóa bộ lọc
          </button>
        )}
      </div>
      {bestSellers.status === 'ready' && bestSellers.data.length > 0 && (
        <section className="search-empty__suggestions" aria-labelledby="search-best-sellers">
          <h2 id="search-best-sellers" className="search-empty__title">
            Sản phẩm bán chạy{filters.categoryId ? ' cùng danh mục' : ` tại ${ACTIVE_CITY.name}`}
          </h2>
          <ProductGrid status="ready" products={bestSellers.data} />
        </section>
      )}
    </div>
  )
}

// Co tu khoa: AI Search (PBL6-34); khong co tu khoa: duyet danh muc qua Product Service.
export default function SearchPage() {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const results = useProductSearch(filters)
  const tree = useCategoryTree()
  const path = findPath(tree.data || [], filters.categoryId)
  const category = path[path.length - 1]
  // Danh muc con cua danh muc dang xem; chua chon thi hien cap 1.
  const subcategories = (category ? category.children : tree.data) || []

  function update(changes, { keepPage = false } = {}) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === null || value === '') next.delete(key)
      else next.set(key, String(value))
    }
    if (!keepPage) next.delete('page')
    setParams(next)
  }

  const heading = filters.keyword
    ? `Kết quả cho “${filters.keyword}”`
    : category
      ? category.name
      : filters.sort === PRODUCT_SORT.bestSelling
        ? `Bán chạy tại ${ACTIVE_CITY.name}`
        : 'Tất cả sản phẩm'
  const data = results.status === 'ready' ? results.data : null
  const total = data?.totalElements
  const hasFilters =
    filters.minPrice !== undefined ||
    filters.maxPrice !== undefined ||
    Boolean(filters.minRating) ||
    Boolean(filters.keyword && filters.categoryId)
  const clearFilters = () =>
    update({
      minPrice: undefined,
      maxPrice: undefined,
      rating: undefined,
      ...(filters.keyword ? { categoryId: undefined } : {}),
    })
  const noResults = data && total === 0 && filters.page === 0

  return (
    <main className="home catalog-page">
      <nav className="catalog-breadcrumb" aria-label="Đường dẫn">
        <Link to="/">Trang chủ</Link>
        {filters.keyword && (
          <span>
            <span aria-hidden="true">›</span>
            {path.length ? (
              <Link to={`/search?q=${encodeURIComponent(filters.keyword)}`}>
                “{filters.keyword}”
              </Link>
            ) : (
              <span aria-current="page">“{filters.keyword}”</span>
            )}
          </span>
        )}
        {path.map((node, index) => (
          <span key={node.id}>
            <span aria-hidden="true">›</span>
            {index === path.length - 1 ? (
              <span aria-current="page">{node.name}</span>
            ) : (
              <Link to={`/search?categoryId=${encodeURIComponent(node.id)}`}>{node.name}</Link>
            )}
          </span>
        ))}
      </nav>
      <div className="catalog-layout">
        <aside className="catalog-filter" aria-label="Bộ lọc">
          <h2 className="catalog-filter__title">Bộ lọc</h2>
          {subcategories.length > 0 && (
            <section className="catalog-filter__group" aria-labelledby="filter-category">
              <h3 id="filter-category">Danh mục sản phẩm</h3>
              <ul className="catalog-filter__list">
                {category && (
                  <li>
                    <button
                      type="button"
                      className="catalog-filter__link"
                      onClick={() => update({ categoryId: category.parentId || '' })}
                    >
                      ‹ Quay lại
                    </button>
                  </li>
                )}
                {subcategories.map((node) => (
                  <li key={node.id}>
                    <button
                      type="button"
                      className="catalog-filter__link"
                      onClick={() => update({ categoryId: node.id })}
                    >
                      {node.name}
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}
          <section className="catalog-filter__group" aria-labelledby="filter-price">
            <h3 id="filter-price">Khoảng giá (₫)</h3>
            <PriceFilter
              key={`${filters.minPrice}-${filters.maxPrice}`}
              filters={filters}
              onApply={(range) => update(range)}
            />
          </section>
          {!filters.keyword && (
            <section className="catalog-filter__group" aria-labelledby="filter-rating">
              <h3 id="filter-rating">Đánh giá</h3>
              <div className="catalog-filter__ratings" role="group" aria-labelledby="filter-rating">
                {RATINGS.map((rating) => (
                  <button
                    key={rating}
                    type="button"
                    className="catalog-filter__rating"
                    aria-pressed={filters.minRating === rating}
                    onClick={() =>
                      update({ rating: filters.minRating === rating ? undefined : rating })
                    }
                  >
                    <span className="catalog-filter__stars" aria-hidden="true">
                      {Array.from({ length: 5 }, (_, index) => (
                        <StarIcon key={index} className={index < rating ? '' : 'is-empty'} />
                      ))}
                    </span>
                    {rating === 5 ? '5 sao' : `Từ ${rating} sao`}
                  </button>
                ))}
              </div>
            </section>
          )}
          {hasFilters && (
            <button
              type="button"
              className="text-button catalog-filter__clear"
              onClick={clearFilters}
            >
              Xoá bộ lọc
            </button>
          )}
        </aside>

        <section className="catalog-results" aria-labelledby="search-title">
          <div className="section-header">
            <div className="section-header__title">
              <h1 id="search-title">{heading}</h1>
              {total != null && (
                <span className="section-header__badge" role="status">
                  {filters.keyword ? `Tìm thấy ${total} sản phẩm phù hợp` : `${total} sản phẩm`}
                </span>
              )}
            </div>
          </div>
          {data?.fallback && (
            <p className="search-fallback" role="status">
              <strong>Tìm kiếm cơ bản đang hoạt động.</strong> Gợi ý thông minh tạm gián đoạn, kết
              quả đang được tìm theo tên sản phẩm.
            </p>
          )}
          <div className="catalog-sort" role="group" aria-label="Sắp xếp">
            <span>Sắp xếp:</span>
            {sortOptions(filters.keyword).map(([value, label]) => (
              <button
                key={value}
                type="button"
                aria-pressed={filters.sort === value}
                onClick={() =>
                  update({ sort: value === defaultSort(filters.keyword) ? undefined : value })
                }
              >
                {label}
              </button>
            ))}
          </div>
          {noResults ? (
            <NoResults filters={filters} hasFilters={hasFilters} onClearFilters={clearFilters} />
          ) : (
            <ProductGrid
              status={results.status}
              products={data?.content}
              error={results.error}
              onRetry={results.reload}
              emptyMessage="Không có sản phẩm ở trang này."
            />
          )}
          {data && !noResults && (
            <Pagination
              page={data.page ?? filters.page}
              totalPages={data.totalPages}
              onChange={(page) => {
                update({ page: page > 0 ? page + 1 : undefined }, { keepPage: true })
                window.scrollTo?.({ top: 0, behavior: 'smooth' })
              }}
            />
          )}
        </section>
      </div>
    </main>
  )
}
