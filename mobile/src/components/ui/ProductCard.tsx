import { Ionicons } from '@expo/vector-icons'
import { Image } from 'expo-image'
import { Pressable, StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native'

import { AppText } from '@/components/ui/AppText'
import { Ribbon, type RibbonProps } from '@/components/ui/StatusBadge'
import { formatVnd } from '@/lib/format'
import { colors, radius } from '@/theme'

export interface ProductCardProps {
  name: string
  price: number
  imageUrl?: string | null
  /** 0-5. Row is hidden when undefined. */
  rating?: number | null
  soldCount?: number | null
  ribbon?: { label: string; tone?: RibbonProps['tone'] }
  /** Card width; omit for 100% of the parent (use in a 2-column grid by passing (W-12)/2). */
  width?: number
  onPress?: () => void
  style?: StyleProp<ViewStyle>
  testID?: string
}

/** Product tile: square image, name (2 lines), rating row and price. */
export function ProductCard({
  name,
  price,
  imageUrl,
  rating,
  soldCount,
  ribbon,
  width,
  onPress,
  style,
  testID,
}: ProductCardProps) {
  const meta: string[] = []
  if (soldCount !== null && soldCount !== undefined) meta.push(`${soldCount} đã bán`)
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={name}
      onPress={onPress}
      style={({ pressed }) => [
        styles.card,
        width ? { width } : null,
        pressed && { backgroundColor: colors.surfaceSubtle },
        style,
      ]}
    >
      <View style={styles.imageWrap}>
        <Image
          source={imageUrl ? { uri: imageUrl } : undefined}
          style={styles.image}
          contentFit="cover"
          transition={150}
        />
        {ribbon ? <Ribbon label={ribbon.label} tone={ribbon.tone} style={styles.ribbon} /> : null}
      </View>
      <View style={styles.body}>
        <AppText variant="label" numberOfLines={2} style={styles.name}>
          {name}
        </AppText>
        {rating !== null && rating !== undefined ? (
          <View style={styles.ratingRow}>
            <Ionicons name="star" size={12} color={colors.accent500} />
            <AppText variant="caption" color="textSecondary">
              {[rating.toFixed(1), ...meta].join(' · ')}
            </AppText>
          </View>
        ) : meta.length ? (
          <AppText variant="caption" color="textSecondary">
            {meta.join(' · ')}
          </AppText>
        ) : null}
        <AppText variant="label" color="primary600" style={styles.price}>
          {formatVnd(price)}
        </AppText>
      </View>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  imageWrap: { aspectRatio: 1, backgroundColor: colors.surfaceMuted },
  image: { width: '100%', height: '100%' },
  ribbon: { position: 'absolute', top: 8, left: 8 },
  body: { padding: 12, gap: 4 },
  name: { minHeight: 42 },
  ratingRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  price: { fontSize: 16, lineHeight: 24 },
})
