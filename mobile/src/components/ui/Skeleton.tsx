import { useEffect, useState } from 'react'
import { Animated, type DimensionValue, type StyleProp, type ViewStyle } from 'react-native'

import { colors, radius } from '@/theme'

export interface SkeletonProps {
  width?: DimensionValue
  height?: number
  radius?: number
  style?: StyleProp<ViewStyle>
  testID?: string
}

/** Pulsing placeholder block (opacity 0.6 <-> 1, 900ms). */
export function Skeleton({
  width = '100%',
  height = 16,
  radius: r = radius.sm,
  style,
  testID,
}: SkeletonProps) {
  const opacity = useState(() => new Animated.Value(0.6))[0]
  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(opacity, { toValue: 1, duration: 900, useNativeDriver: true }),
        Animated.timing(opacity, { toValue: 0.6, duration: 900, useNativeDriver: true }),
      ]),
    )
    loop.start()
    return () => loop.stop()
  }, [opacity])
  return (
    <Animated.View
      testID={testID}
      style={[
        { width, height, borderRadius: r, backgroundColor: colors.surfaceMuted, opacity },
        style,
      ]}
    />
  )
}
