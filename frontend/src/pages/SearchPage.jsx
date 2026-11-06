import { useSearchParams } from 'react-router-dom'
import ProductGrid from '../components/ProductGrid'
import { useProductSearch, useTopCategories } from '../hooks/useCatalog'
import { PRODUCT_SORT } from '../services/api/productApi'
import { ACTIVE_CITY } from '../data/city'
import '../components/home.css'

const SORTS = Object.values(PRODUCT_SORT)

// Ket qua tam thoi tu Product Service; PBL6-34 se thay bang Search Service (goi y, tim bang anh).
export default function SearchPage() {
  const [params] = useSearchParams()
  const keyword = params.get('q')?.trim() || ''
  const categoryId = params.get('categoryId') || ''
  const sort = SORTS.includes(params.get('sort')) ? params.get('sort') : PRODUCT_SORT.newest
  const results = useProductSearch({ keyword, categoryId, sort })
  const categories = useTopCategories()
  const category = categories.data?.find((item) => item.id === categoryId)

  const heading = keyword
    ? `Kết quả cho “${keyword}”`
    : category
      ? category.name
      : sort === PRODUCT_SORT.bestSelling
        ? `Bán chạy tại ${ACTIVE_CITY.name}`
        : 'Tất cả sản phẩm'
  const total = results.status === 'ready' ? results.data?.totalElements : null

  return (
    <main className="home">
      <section className="home-section" aria-labelledby="search-title">
        <div className="section-header">
          <div className="section-header__title">
            <h1 id="search-title">{heading}</h1>
            {total != null && (
              <span className="section-header__badge" role="status">
                {total} sản phẩm
              </span>
            )}
          </div>
        </div>
        <ProductGrid
          status={results.status}
          products={results.data?.content}
          error={results.error}
          onRetry={results.reload}
          emptyMessage="Không tìm thấy sản phẩm phù hợp. Thử từ khóa khác nhé."
        />
      </section>
    </main>
  )
}
