import { Ionicons } from '@expo/vector-icons'
import { Pressable, StyleSheet, View } from 'react-native'

import { AppText, Card } from '@/components/ui'
import { colors } from '@/theme'

const CAPTIONS = ['Rất tệ', 'Không hài lòng', 'Bình thường', 'Hài lòng', 'Rất hài lòng']

interface Props {
  value: number
  onChange: (rating: number) => void
  disabled?: boolean
}

/** "★★★★★  5/5" card with a caption for the chosen rating. */
export function RatingStars({ value, onChange, disabled }: Props) {
  return (
    <Card testID="review-rating">
      <View style={styles.row}>
        {[1, 2, 3, 4, 5].map((n) => (
          <Pressable
            key={n}
            testID={`review-star-${n}`}
            accessibilityRole="button"
            accessibilityLabel={`${n} sao`}
            disabled={disabled}
            hitSlop={6}
            onPress={() => onChange(n)}
          >
            <Ionicons name={n <= value ? 'star' : 'star-outline'} size={22} color={colors.text} />
          </Pressable>
        ))}
        <AppText variant="label">{`  ${value}/5`}</AppText>
      </View>
      <AppText>{CAPTIONS[value - 1]}</AppText>
    </Card>
  )
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 4 },
})
