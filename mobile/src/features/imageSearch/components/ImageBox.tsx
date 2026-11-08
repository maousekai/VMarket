import { Image } from 'expo-image'
import { StyleSheet } from 'react-native'

import { colors, radius } from '@/theme'

/** Letterboxed photo preview used on the preview and results screens. */
export function ImageBox({
  uri,
  height,
  testID,
}: {
  uri: string
  height: number
  testID?: string
}) {
  return (
    <Image
      testID={testID}
      source={{ uri }}
      contentFit="contain"
      accessibilityLabel="Ảnh sản phẩm"
      style={[styles.image, { height }]}
    />
  )
}

const styles = StyleSheet.create({
  image: { width: '100%', borderRadius: radius.md, backgroundColor: colors.surfaceSubtle },
})
