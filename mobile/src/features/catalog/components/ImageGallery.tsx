import { Ionicons } from '@expo/vector-icons'
import { Image } from 'expo-image'
import { useState } from 'react'
import { FlatList, StyleSheet, View, useWindowDimensions } from 'react-native'

import { colors, layout, radius } from '@/theme'

import { resolveImageUrl } from '../utils'

const HEIGHT = 240

/** Horizontally paged product images (240px high) with dot indicators. */
export function ImageGallery({ urls }: { urls: string[] }) {
  const { width: screenWidth } = useWindowDimensions()
  const width = screenWidth - layout.screenPaddingX * 2
  const [index, setIndex] = useState(0)
  const images = urls.map(resolveImageUrl).filter((u): u is string => !!u)

  if (!images.length) {
    return (
      <View style={[styles.frame, styles.placeholder, { width }]}>
        <Ionicons name="image-outline" size={32} color={colors.textSecondary} />
      </View>
    )
  }
  return (
    <View style={[styles.frame, { width }]} testID="product-gallery">
      <FlatList
        data={images}
        keyExtractor={(uri, i) => `${i}-${uri}`}
        horizontal
        pagingEnabled
        showsHorizontalScrollIndicator={false}
        onMomentumScrollEnd={(e) => setIndex(Math.round(e.nativeEvent.contentOffset.x / width))}
        renderItem={({ item }) => (
          <Image source={{ uri: item }} style={{ width, height: HEIGHT }} contentFit="cover" />
        )}
      />
      {images.length > 1 ? (
        <View style={styles.dots} pointerEvents="none">
          {images.map((_, i) => (
            <View key={i} style={[styles.dot, i === index && styles.dotActive]} />
          ))}
        </View>
      ) : null}
    </View>
  )
}

const styles = StyleSheet.create({
  frame: {
    height: HEIGHT,
    borderRadius: radius.md,
    overflow: 'hidden',
    backgroundColor: colors.surfaceMuted,
  },
  placeholder: { alignItems: 'center', justifyContent: 'center' },
  dots: {
    position: 'absolute',
    bottom: 8,
    left: 0,
    right: 0,
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 6,
  },
  dot: { width: 6, height: 6, borderRadius: 3, backgroundColor: colors.border },
  dotActive: { backgroundColor: colors.primary600 },
})
