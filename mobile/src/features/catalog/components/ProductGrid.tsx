import { router } from 'expo-router'
import { StyleSheet, View, useWindowDimensions } from 'react-native'

import type { ProductSummary } from '@/api/catalog'
import { ProductCard } from '@/components/ui'
import { layout } from '@/theme'

import { productHref } from '../routes'
import { coverImage, productPrice } from '../utils'

const GAP = 12

export const openProduct = (id: string) => router.push(productHref(id))

/** Two-column grid of ProductCards for inside a padded screen. */
export function ProductGrid({ items, testID }: { items: ProductSummary[]; testID?: string }) {
  const { width } = useWindowDimensions()
  const cardWidth = Math.floor((width - layout.screenPaddingX * 2 - GAP) / 2)
  return (
    <View testID={testID} style={styles.grid}>
      {items.map((p) => (
        <ProductCard
          key={p.id}
          testID={`product-card-${p.id}`}
          width={cardWidth}
          name={p.name}
          price={productPrice(p)}
          imageUrl={coverImage(p)}
          rating={p.ratingCount > 0 ? p.ratingAverage : undefined}
          soldCount={p.soldCount > 0 ? p.soldCount : undefined}
          ribbon={p.availableStock <= 0 ? { label: 'Hết hàng', tone: 'danger' } : undefined}
          onPress={() => openProduct(p.id)}
        />
      ))}
    </View>
  )
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: GAP },
})
