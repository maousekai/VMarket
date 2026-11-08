import { router } from 'expo-router'

import { Button, EmptyState, InfoCard } from '@/components/ui'
import { formatVnd } from '@/lib/format'

import { useBestSellers, useCategories } from '../hooks'
import { imageSearchHref, searchHref } from '../routes'
import { findCategoryName } from '../utils'
import { LineItem } from './LineItem'
import { openProduct } from './ProductGrid'

/** Zero-result state (Figma 2508:1854): hint card, best sellers and image-search entry. */
export function NoResults({
  categoryId,
  hasKeyword,
}: {
  categoryId?: string
  hasKeyword: boolean
}) {
  const categories = useCategories()
  const best = useBestSellers(categoryId)
  const categoryName = findCategoryName(categories.data, categoryId)
  const items = best.data ?? []

  if (!hasKeyword && !categoryId) {
    return (
      <EmptyState
        testID="results-empty"
        heading="Chưa có sản phẩm"
        title="Chưa có sản phẩm"
        body="Danh mục này chưa có sản phẩm nào. Vui lòng quay lại sau."
        secondaryLabel="Về trang chủ"
        onSecondary={() => router.replace('/(buyer)')}
      />
    )
  }

  return (
    <>
      <InfoCard
        testID="results-empty"
        title="Không tìm thấy kết quả"
        body={
          categoryName
            ? `Thử từ khóa khác hoặc xem sản phẩm bán chạy trong danh mục ${categoryName}.`
            : 'Thử từ khóa khác hoặc xem sản phẩm bán chạy.'
        }
      />
      {items.length ? (
        <InfoCard title={categoryName ? `Bán chạy · ${categoryName}` : 'Bán chạy'}>
          {items.map((p) => (
            <LineItem
              key={p.id}
              testID={`best-${p.id}`}
              text={`${p.name} · ${formatVnd(p.minPrice)}`}
              onPress={() => openProduct(p.id)}
            />
          ))}
        </InfoCard>
      ) : null}
      <Button
        testID="results-image-search"
        title="Tìm bằng ảnh"
        onPress={() => router.push(imageSearchHref)}
      />
      <Button
        testID="results-clear"
        title="Xóa tìm kiếm"
        kind="secondary"
        onPress={() => router.replace(searchHref())}
      />
    </>
  )
}
