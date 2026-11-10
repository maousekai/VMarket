import { toQR } from 'toqr'
import { useMemo } from 'react'
import { View } from 'react-native'
import Svg, { Path } from 'react-native-svg'

import { colors } from '@/theme'

export interface QrCodeProps {
  value: string
  size?: number
  testID?: string
}

/** Renders a QR code (black on white, with a 4-module quiet zone) from a raw payload string. */
export function QrCode({ value, size = 246, testID }: QrCodeProps) {
  const { modules, path } = useMemo(() => {
    const matrix = toQR(value)
    const n = Math.round(Math.sqrt(matrix.length))
    let d = ''
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (matrix[y * n + x]) d += `M${x + 4} ${y + 4}h1v1h-1z`
      }
    }
    return { modules: n + 8, path: d }
  }, [value])

  return (
    <View
      testID={testID}
      accessibilityLabel="Mã QR thanh toán"
      style={{ width: size, height: size }}
    >
      <Svg width={size} height={size} viewBox={`0 0 ${modules} ${modules}`}>
        <Path d={`M0 0h${modules}v${modules}h-${modules}z`} fill={colors.surface} />
        <Path d={path} fill="#000000" />
      </Svg>
    </View>
  )
}
