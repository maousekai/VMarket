import { useAuth } from '../auth/useAuth'
import FeaturedShops from '../components/FeaturedShops'
import FormMessage from '../components/FormMessage'
import HeroCarousel from '../components/HeroCarousel'
import ProductGrid from '../components/ProductGrid'
import SectionHeader from '../components/SectionHeader'
import { ACTIVE_CITY } from '../data/city'
import { homeBanners } from '../data/homeBanners'
import { useFeaturedShops, useHomeProducts } from '../hooks/useCatalog'
import { PRODUCT_SORT } from '../services/api/productApi'
import '../components/home.css'

export default function HomePage() {
  const auth = useAuth()
  const feed = useHomeProducts(auth.session?.userId)
  const shops = useFeaturedShops()
  // Only call it "for you" when the picks really are personal.
  const personal = feed.status === 'ready' && feed.data.source === 'personal'

  return (
    <main className="home">
      {auth.bootstrapState === 'error' && (
        <div className="session-notice">
          <FormMessage error={auth.error} />
          <button className="text-button" onClick={() => auth.refresh().catch(() => {})}>
            Thử lại kết nối
          </button>
        </div>
      )}
      <h1 className="visually-hidden">VMarket — Chợ trực tuyến {ACTIVE_CITY.name}</h1>
      <HeroCarousel slides={homeBanners} />
      <section className="home-section" aria-labelledby="home-products-title">
        <SectionHeader
          id="home-products-title"
          title={personal ? 'Sản phẩm dành cho bạn' : `Bán chạy tại ${ACTIVE_CITY.name}`}
          badge={
            personal
              ? 'Gợi ý dành cho bạn'
              : auth.session
                ? 'Được mua nhiều nhất'
                : 'Gợi ý cho khách mới'
          }
          linkTo={`/search?sort=${PRODUCT_SORT.bestSelling}`}
          linkLabel="Xem thêm"
        />
        <ProductGrid
          status={feed.status}
          products={feed.data?.products}
          error={feed.error}
          onRetry={feed.reload}
          emptyMessage="Các nhà bán đang lên kệ sản phẩm. Quay lại sau nhé!"
        />
      </section>
      <FeaturedShops
        title={`Cửa Hàng Nổi Bật Tại ${ACTIVE_CITY.name}`}
        status={shops.status}
        shops={shops.data}
        error={shops.error}
        onRetry={shops.reload}
      />
    </main>
  )
}
