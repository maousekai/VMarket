import { Ionicons } from '@expo/vector-icons'
import { useState } from 'react'
import { Image, StyleSheet, View } from 'react-native'

import { colors } from '@/theme'

/** 96px circle: the user's picture, or the default silhouette (design 1.8). */
export function Avatar({
  uri,
  size = 96,
  testID,
}: {
  uri?: string | null
  size?: number
  testID?: string
}) {
  const [failedUri, setFailedUri] = useState<string | null>(null)
  const showImage = !!uri && failedUri !== uri
  const box = { width: size, height: size, borderRadius: size / 2 }
  return (
    <View testID={testID} style={[styles.circle, box]} accessibilityLabel="Ảnh đại diện">
      {showImage ? (
        <Image source={{ uri }} style={box} onError={() => setFailedUri(uri)} />
      ) : (
        <Ionicons name="person" size={size * 0.55} color={colors.primary600} />
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  circle: {
    backgroundColor: colors.primary50,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
})
