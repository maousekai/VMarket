import { Pressable } from 'react-native'

import { AppText } from '@/components/ui'

/** A tappable text line inside a card (suggestions, product lines): 32px touch target. */
export function LineItem({
  text,
  onPress,
  testID,
}: {
  text: string
  onPress: () => void
  testID?: string
}) {
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      onPress={onPress}
      style={{ minHeight: 32, justifyContent: 'center' }}
    >
      <AppText>{text}</AppText>
    </Pressable>
  )
}
