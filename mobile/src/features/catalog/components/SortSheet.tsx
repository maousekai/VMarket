import { Ionicons } from '@expo/vector-icons'
import { Pressable, StyleSheet } from 'react-native'

import type { SortKey } from '@/api/catalog'
import { AppText, BottomSheet } from '@/components/ui'
import { colors } from '@/theme'

import { SORT_OPTIONS } from '../utils'

export function SortSheet({
  visible,
  value,
  onSelect,
  onClose,
}: {
  visible: boolean
  value: SortKey
  onSelect: (key: SortKey) => void
  onClose: () => void
}) {
  return (
    <BottomSheet visible={visible} onClose={onClose} title="Sắp xếp" testID="sort-sheet">
      {SORT_OPTIONS.map((o) => {
        const selected = o.key === value
        return (
          <Pressable
            key={o.key}
            testID={`sort-${o.key}`}
            accessibilityRole="button"
            accessibilityState={{ selected }}
            onPress={() => onSelect(o.key)}
            style={styles.row}
          >
            <AppText color={selected ? 'primary700' : 'text'}>{o.label}</AppText>
            {selected ? <Ionicons name="checkmark" size={20} color={colors.primary600} /> : null}
          </Pressable>
        )
      })}
    </BottomSheet>
  )
}

const styles = StyleSheet.create({
  row: {
    height: 48,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
})
