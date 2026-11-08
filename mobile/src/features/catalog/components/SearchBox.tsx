import { Ionicons } from '@expo/vector-icons'
import { Pressable, StyleSheet } from 'react-native'

import { AppText } from '@/components/ui'
import { colors, layout, radius } from '@/theme'

export const SEARCH_PLACEHOLDER = 'Tìm sản phẩm, gian hàng…'

/** Read-only search field: the whole box is a button that opens the search-entry screen. */
export function SearchBox({
  value,
  onPress,
  testID,
}: {
  value?: string
  onPress: () => void
  testID?: string
}) {
  return (
    <Pressable
      testID={testID}
      accessibilityRole="search"
      accessibilityLabel="Tìm kiếm"
      onPress={onPress}
      style={styles.box}
    >
      <Ionicons name="search" size={18} color={colors.textSecondary} />
      <AppText color="textSecondary" numberOfLines={1} style={styles.text}>
        {value || SEARCH_PLACEHOLDER}
      </AppText>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  box: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    height: layout.controlHeight,
    paddingHorizontal: 16,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
  },
  text: { flex: 1 },
})
