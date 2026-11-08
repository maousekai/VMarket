import { StyleSheet, View, useWindowDimensions } from 'react-native'

import type { ImageSearchHit } from '@/api/imageSearch'
import { ProductCard } from '@/components/ui'
import { layout } from '@/theme'

const GAP = 12

/** Two-column grid of matches, already sorted by similarity by the server. */
export function SimilarProducts({
  hits,
  onOpen,
}: {
  hits: ImageSearchHit[]
  onOpen: (id: string) => void
}) {
  const { width } = useWindowDimensions()
  const cardWidth = Math.floor((width - layout.screenPaddingX * 2 - GAP) / 2)
  return (
    <View style={styles.grid}>
      {hits.map((hit) => (
        <ProductCard
          key={hit.id}
          testID={`image-search-hit-${hit.id}`}
          width={cardWidth}
          name={hit.name}
          price={hit.minPrice}
          imageUrl={hit.matchedImageUrl ?? hit.imageUrls[0]}
          rating={hit.ratingCount ? hit.ratingAverage : undefined}
          soldCount={hit.soldCount}
          ribbon={
            hit.availableStock === 0
              ? { label: 'Hết hàng', tone: 'danger' }
              : { label: `${Math.round(hit.similarity * 100)}% giống`, tone: 'accent' }
          }
          onPress={() => onOpen(hit.id)}
        />
      ))}
    </View>
  )
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: GAP },
})
