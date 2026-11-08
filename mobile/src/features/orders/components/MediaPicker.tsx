import { Ionicons } from '@expo/vector-icons'
import { Image } from 'expo-image'
import { Pressable, ScrollView, StyleSheet, View } from 'react-native'

import { AppText, InfoCard } from '@/components/ui'
import { pickImages, type PickedImage } from '@/features/orders/media'
import { colors, radius } from '@/theme'

export interface MediaPickerProps {
  /** Field label without the counter, e.g. "Ảnh/video minh chứng". */
  label: string
  required?: boolean
  files: PickedImage[]
  onChange: (files: PickedImage[]) => void
  max: number
  /** Noun after the count: "tệp" | "ảnh". */
  unit: string
  /** Over-limit card title, e.g. "Tối đa 5 ảnh/video". */
  overTitle: string
  /** Body of the idle card, e.g. "Tối đa 5 ảnh cho mỗi đánh giá.". */
  hint: string
  /** Tail of the over-limit card body, e.g. "để gửi yêu cầu.". */
  overSuffix: string
  disabled?: boolean
  testID?: string
}

/** Evidence / photo chooser: counter label, card (or red field when over the limit), thumbnails. */
export function MediaPicker({
  label,
  required,
  files,
  onChange,
  max,
  unit,
  overTitle,
  hint,
  overSuffix,
  disabled,
  testID,
}: MediaPickerProps) {
  const over = files.length > max
  const pick = async () => {
    const picked = await pickImages(max)
    if (picked.length) onChange([...files, ...picked])
  }
  return (
    <View style={styles.root} testID={testID}>
      {over ? (
        <InfoCard
          title={overTitle}
          body={`Bạn đã chọn ${files.length} ${unit}. Bỏ bớt ${files.length - max} ${unit} ${overSuffix}`}
        />
      ) : null}
      <AppText variant="label">{`${label} (${files.length}/${max})${required ? ' *' : ''}`}</AppText>
      {over ? (
        <Pressable
          disabled={disabled}
          onPress={pick}
          style={[styles.field, styles.fieldError]}
          accessibilityRole="button"
        >
          <AppText color="textSecondary">{`Đã chọn ${files.length} ${unit}`}</AppText>
        </Pressable>
      ) : (
        <InfoCard
          testID={testID ? `${testID}-card` : undefined}
          title={`Đã chọn ${files.length} ${unit}`}
          body={hint}
          onPress={disabled ? undefined : pick}
        />
      )}
      {files.length ? (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.row}
        >
          {files.map((f, i) => (
            <View key={`${f.uri}-${i}`} style={styles.thumb}>
              <Image source={{ uri: f.uri }} style={styles.img} contentFit="cover" />
              <Pressable
                accessibilityLabel="Bỏ tệp này"
                hitSlop={8}
                disabled={disabled}
                onPress={() => onChange(files.filter((_, idx) => idx !== i))}
                style={styles.remove}
              >
                <Ionicons name="close" size={14} color={colors.textInverse} />
              </Pressable>
            </View>
          ))}
        </ScrollView>
      ) : null}
    </View>
  )
}

const styles = StyleSheet.create({
  root: { gap: 12 },
  field: {
    height: 48,
    justifyContent: 'center',
    paddingHorizontal: 16,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
  },
  fieldError: { borderColor: colors.danger },
  row: { gap: 8, paddingTop: 6, paddingRight: 6 },
  thumb: { width: 64, height: 64 },
  img: { width: 64, height: 64, borderRadius: radius.md, backgroundColor: colors.surfaceMuted },
  remove: {
    position: 'absolute',
    top: -6,
    right: -6,
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: colors.text,
    alignItems: 'center',
    justifyContent: 'center',
  },
})
