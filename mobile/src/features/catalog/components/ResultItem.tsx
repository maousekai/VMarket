import { Ionicons } from '@expo/vector-icons'
import { Image } from 'expo-image'
import { Pressable, StyleSheet, View } from 'react-native'

import type { ProductSummary } from '@/api/catalog'
import { AppText, Button } from '@/components/ui'
import { formatVnd } from '@/lib/format'
import { colors, radius } from '@/theme'

import { useShop } from '../hooks'
import { coverImage } from '../utils'
import { openProduct } from './ProductGrid'

/** Result row from the Figma search results: 180px image, name, price · shop, CTA. */
export function ResultItem({ product }: { product: ProductSummary }) {
  const shop = useShop(product.shopId)
  const meta = [product.minPrice !== null ? formatVnd(product.minPrice) : null, shop.data?.name]
    .filter(Boolean)
    .join(' · ')
  const open = () => openProduct(product.id)
  const uri = coverImage(product)
  return (
    <View style={styles.item} testID={`result-${product.id}`}>
      <Pressable accessibilityRole="imagebutton" accessibilityLabel={product.name} onPress={open}>
        <View style={styles.image}>
          {uri ? (
            <Image source={{ uri }} style={styles.fill} contentFit="cover" transition={150} />
          ) : (
            <View style={[styles.fill, styles.placeholder]}>
              <Ionicons name="image-outline" size={32} color={colors.textSecondary} />
            </View>
          )}
        </View>
      </Pressable>
      <Pressable onPress={open}>
        <AppText variant="label">{product.name}</AppText>
      </Pressable>
      {meta ? <AppText>{meta}</AppText> : null}
      <Button title="Xem chi tiết" onPress={open} testID={`result-${product.id}-open`} />
    </View>
  )
}

const styles = StyleSheet.create({
  item: { gap: 12 },
  image: { height: 180, borderRadius: radius.md, overflow: 'hidden' },
  fill: { width: '100%', height: '100%' },
  placeholder: {
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceMuted,
  },
})
