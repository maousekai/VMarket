import { Ionicons } from '@expo/vector-icons'
import { Image } from 'expo-image'
import { Pressable, View } from 'react-native'

import type { CartItem } from '@/api/cart'
import { AppText, Card, Divider, QuantityStepper } from '@/components/ui'
import { formatVnd } from '@/lib/format'
import { colors, radius } from '@/theme'
import type { CartGroupView, CartLineView } from '../hooks'

export interface ShopGroupCardProps {
  group: CartGroupView
  busy: boolean
  onQuantity: (item: CartItem, quantity: number) => void
  onRemove: (line: CartLineView) => void
}

type LineRowProps = Omit<ShopGroupCardProps, 'group'> & { line: CartLineView }

const lineKey = (item: CartItem) => `${item.productId}-${item.variantId ?? 'base'}`

function LineRow({ line, busy, onQuantity, onRemove }: LineRowProps) {
  const { item } = line
  const key = lineKey(item)
  return (
    <View style={{ flexDirection: 'row', gap: 12 }} testID={`cart-line-${key}`}>
      <View
        style={{
          width: 64,
          height: 64,
          borderRadius: radius.sm,
          backgroundColor: colors.surfaceMuted,
          overflow: 'hidden',
        }}
      >
        {line.imageUrl ? (
          <Image source={{ uri: line.imageUrl }} style={{ flex: 1 }} contentFit="cover" />
        ) : null}
      </View>
      <View style={{ flex: 1, gap: 4 }}>
        <AppText numberOfLines={2}>{line.name}</AppText>
        {line.variant ? (
          <AppText variant="caption" color="textSecondary">
            {line.variant}
          </AppText>
        ) : null}
        <AppText variant="label" color="primary700">
          {formatVnd(item.unitPrice)}
        </AppText>
        <View
          style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}
        >
          <QuantityStepper
            testID={`cart-qty-${key}`}
            value={item.quantity}
            max={Math.max(line.maxQuantity, 1)}
            disabled={busy}
            onChange={(q) => onQuantity(item, q)}
          />
          <Pressable
            testID={`cart-remove-${key}`}
            accessibilityRole="button"
            accessibilityLabel="Xóa sản phẩm khỏi giỏ hàng"
            hitSlop={8}
            disabled={busy}
            onPress={() => onRemove(line)}
          >
            <Ionicons name="trash-outline" size={20} color={colors.danger} />
          </Pressable>
        </View>
      </View>
    </View>
  )
}

/** One card per shop: lines with stepper/remove and the shop subtotal. */
export function ShopGroupCard({ group, busy, onQuantity, onRemove }: ShopGroupCardProps) {
  return (
    <Card testID={`cart-group-${group.shopId}`}>
      <AppText variant="label">{group.shopName}</AppText>
      {group.lines.map((line) => (
        <LineRow
          key={lineKey(line.item)}
          line={line}
          busy={busy}
          onQuantity={onQuantity}
          onRemove={onRemove}
        />
      ))}
      <Divider />
      <AppText>Tiền hàng: {formatVnd(group.subtotal)}</AppText>
    </Card>
  )
}
