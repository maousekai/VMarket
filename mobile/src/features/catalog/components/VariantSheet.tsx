import { StyleSheet, View } from 'react-native'

import type { Variant } from '@/api/catalog'
import { AppText, BottomSheet, Button, Chip } from '@/components/ui'

import { attributeAxes, axisValueInStock, pickVariant } from '../utils'

/** One chip group per attribute axis; out-of-stock combinations stay selectable (spec 7.6). */
export function VariantSheet({
  visible,
  variants,
  selected,
  onSelect,
  onClose,
}: {
  visible: boolean
  variants: Variant[]
  selected: Variant | undefined
  onSelect: (variant: Variant) => void
  onClose: () => void
}) {
  const axes = attributeAxes(variants)
  return (
    <BottomSheet visible={visible} onClose={onClose} title="Chọn phân loại" testID="variant-sheet">
      {Object.entries(axes).map(([key, values]) => (
        <View key={key} style={styles.group}>
          <AppText variant="label">{key}</AppText>
          <View style={styles.chips}>
            {values.map((value) => {
              const inStock = axisValueInStock(variants, selected, key, value)
              const picked = selected?.attributes[key] === value
              return (
                <Chip
                  key={value}
                  testID={`variant-${key}-${value}`}
                  label={inStock || picked ? value : `${value} (hết hàng)`}
                  selected={picked}
                  onPress={() => {
                    const next = pickVariant(variants, selected, key, value)
                    if (next) onSelect(next)
                  }}
                  style={!inStock && !picked ? styles.soldOut : undefined}
                />
              )
            })}
          </View>
        </View>
      ))}
      <Button title="Xác nhận" onPress={onClose} testID="variant-confirm" />
    </BottomSheet>
  )
}

const styles = StyleSheet.create({
  group: { gap: 8 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  soldOut: { opacity: 0.6 },
})
