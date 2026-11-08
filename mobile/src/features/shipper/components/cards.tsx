import type { ReactNode } from 'react'
import { Linking, Pressable, View } from 'react-native'

import { AppText, Card, useToast } from '@/components/ui'
import { formatPhone } from '@/lib/format'

import type { OrderItem, PickupShop, Receiver } from '../lib/order'

/** InfoCard variant with `text` coloured lines (Figma body is content-default, not secondary). */
export function LinesCard({
  title,
  lines,
  children,
  testID,
}: {
  title: string
  lines?: (string | null | undefined)[]
  children?: ReactNode
  testID?: string
}) {
  return (
    <Card testID={testID}>
      <AppText variant="label">{title}</AppText>
      {(lines ?? []).filter(Boolean).map((line, i) => (
        <AppText key={`${i}-${line}`} variant="body">
          {line}
        </AppText>
      ))}
      {children}
    </Card>
  )
}

export function PhoneLink({ phone, prefix }: { phone: string; prefix?: string }) {
  const toast = useToast()
  if (!phone) return null
  return (
    <Pressable
      testID="shipper-call"
      accessibilityRole="link"
      accessibilityLabel={`Gọi ${formatPhone(phone)}`}
      hitSlop={6}
      onPress={() =>
        Linking.openURL(`tel:${phone}`).catch(() =>
          toast.show({ type: 'error', message: 'Thiết bị không hỗ trợ gọi điện.' }),
        )
      }
    >
      <AppText variant="body" color="primary600">
        {prefix}
        {formatPhone(phone)}
      </AppText>
    </Pressable>
  )
}

export function ReceiverCard({ receiver }: { receiver: Receiver }) {
  return (
    <LinesCard
      testID="shipper-receiver-card"
      title={`Giao cho ${receiver.name || 'người nhận'}`}
      lines={[receiver.address]}
    >
      <PhoneLink phone={receiver.phone} />
    </LinesCard>
  )
}

/** Item lines `name · variant` + `Số lượng: n` for the items sold by `shopId` (all when omitted). */
export function itemLines(
  items: OrderItem[],
  labels: Record<string, string>,
  shopId?: string,
): string[] {
  return items
    .filter((i) => !shopId || i.shopId === shopId)
    .flatMap((i) => [labels[i.variantId] || 'Sản phẩm', `Số lượng: ${i.quantity}`])
}

/** One "Lấy hàng · {shop}" card per shop of the order (multi-shop orders have several). */
export function PickupCards({
  shops,
  items,
  labels,
}: {
  shops: PickupShop[]
  items: OrderItem[]
  labels: Record<string, string>
}) {
  if (shops.length === 0) {
    return <LinesCard title="Lấy hàng" lines={[...itemLines(items, labels)]} />
  }
  return (
    <View style={{ gap: 12 }}>
      {shops.map((s) => (
        <LinesCard
          key={s.shopId}
          testID="shipper-pickup-card"
          title={`Lấy hàng · ${s.name}`}
          lines={[s.address, ...itemLines(items, labels, s.shopId)]}
        >
          <PhoneLink phone={s.phone} />
        </LinesCard>
      ))}
    </View>
  )
}
