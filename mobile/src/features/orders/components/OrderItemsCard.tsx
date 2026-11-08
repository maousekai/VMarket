import { View } from 'react-native'

import type { Order, OrderItem } from '@/api/orders'
import { AppText, Card } from '@/components/ui'
import { itemDetailLine, type ItemLabel } from '@/features/orders/text'

interface Props {
  order: Order
  label: (item: OrderItem) => ItemLabel
  shopName: (shopId: string) => string
}

/** Items grouped per shop: one card per shop ("Sản phẩm" when the order has a single shop). */
export function OrderItemsCard({ order, label, shopName }: Props) {
  const shopIds = [...new Set(order.items.map((i) => i.shopId))]
  return (
    <>
      {shopIds.map((shopId) => (
        <Card key={shopId} testID={`order-items-${shopId}`}>
          <AppText variant="label">{shopIds.length > 1 ? shopName(shopId) : 'Sản phẩm'}</AppText>
          {order.items
            .filter((i) => i.shopId === shopId)
            .map((item, idx) => {
              const l = label(item)
              return (
                <View key={item.id ?? `${item.productId}-${item.variantId}-${idx}`}>
                  <AppText>{l.name}</AppText>
                  <AppText color="textSecondary">{itemDetailLine(item, l.variant)}</AppText>
                </View>
              )
            })}
        </Card>
      ))}
    </>
  )
}
