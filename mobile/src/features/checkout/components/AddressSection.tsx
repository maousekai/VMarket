import { router, type Href } from 'expo-router'
import { useState } from 'react'
import { Pressable, View } from 'react-native'

import type { Address } from '@/api/checkout'
import { AppText, BottomSheet, Button, InfoCard, Skeleton, TextField } from '@/components/ui'
import { colors, radius } from '@/theme'
import { addressLine, recipientLine } from '../format'

/** The address book lives on the account screen (list + add/edit/delete). */
export const ADDRESS_BOOK = '/account' as Href
const ADDRESS_NEW = '/account/address/new' as Href

export interface AddressSectionProps {
  addresses: Address[] | undefined
  loading: boolean
  selectedId: string | undefined
  onSelect: (id: string) => void
}

/** "Địa chỉ nhận hàng": read-only selector + sheet to pick from the address book. */
export function AddressSection({ addresses, loading, selectedId, onSelect }: AddressSectionProps) {
  const [open, setOpen] = useState(false)
  const selected = addresses?.find((a) => a.id === selectedId)

  if (loading) return <Skeleton height={48} />
  if (!addresses?.length) {
    return (
      <>
        <AppText variant="label">Địa chỉ nhận hàng</AppText>
        <InfoCard title="Chưa có địa chỉ nhận hàng" body="Thêm địa chỉ để đặt hàng." />
        <Button
          title="Thêm địa chỉ"
          kind="secondary"
          onPress={() => router.push(ADDRESS_NEW)}
          testID="checkout-add-address"
        />
      </>
    )
  }

  return (
    <>
      <AppText variant="label">Địa chỉ nhận hàng</AppText>
      <TextField
        picker
        value={selected ? `${selected.streetAddress}, ${selected.ward}` : ''}
        placeholder="Chọn địa chỉ"
        onChangeText={() => undefined}
        onPress={() => setOpen(true)}
        testID="checkout-address"
      />
      <Button
        title="Sửa địa chỉ"
        kind="secondary"
        onPress={() => router.push(ADDRESS_BOOK)}
        testID="checkout-edit-address"
      />
      <BottomSheet visible={open} onClose={() => setOpen(false)} title="Chọn địa chỉ nhận hàng">
        <View style={{ gap: 8 }}>
          {addresses.map((a) => {
            const active = a.id === selectedId
            return (
              <Pressable
                key={a.id}
                testID={`address-option-${a.id}`}
                accessibilityRole="radio"
                accessibilityState={{ selected: active }}
                onPress={() => {
                  onSelect(a.id)
                  setOpen(false)
                }}
                style={{
                  padding: 12,
                  gap: 4,
                  borderRadius: radius.md,
                  borderWidth: 1,
                  borderColor: active ? colors.primary600 : colors.border,
                  backgroundColor: active ? colors.primary50 : colors.surface,
                }}
              >
                <AppText variant="label">{recipientLine(a)}</AppText>
                <AppText color="textSecondary">{addressLine(a)}</AppText>
                {a.isDefault ? (
                  <AppText variant="caption" color="primary700">
                    Mặc định
                  </AppText>
                ) : null}
              </Pressable>
            )
          })}
        </View>
      </BottomSheet>
    </>
  )
}
