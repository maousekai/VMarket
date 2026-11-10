import { Camera } from 'expo-camera'
import { router, useFocusEffect } from 'expo-router'
import { useCallback, useState } from 'react'
import { Platform, StyleSheet, View } from 'react-native'

import { AppText, Button, Screen } from '@/components/ui'
import { useImageSearch } from '@/features/imageSearch/ImageSearchContext'
import { isTooLarge } from '@/features/imageSearch/imageFile'
import { goBackOrHome, href } from '@/features/imageSearch/navigation'
import { useImageSource } from '@/features/imageSearch/useImageSource'
import { colors } from '@/theme'

const CAMERA_AVAILABLE = Platform.OS !== 'web'

/** Frame 3.3 - entry: take a photo or pick one from the device. */
export default function ImageSearchEntryScreen() {
  const { reset } = useImageSearch()
  const { busy, pickFromLibrary } = useImageSource()
  const [checking, setChecking] = useState(false)

  // Coming back here (e.g. "Tìm bằng ảnh khác") starts a fresh search.
  useFocusEffect(useCallback(() => reset(), [reset]))

  const openCamera = async () => {
    if (checking) return
    setChecking(true)
    try {
      const permission = await Camera.getCameraPermissionsAsync()
      router.push(href(permission.granted ? 'camera' : 'permission'))
    } finally {
      setChecking(false)
    }
  }

  const pickImage = async () => {
    const image = await pickFromLibrary()
    if (image) router.push(href(isTooLarge(image) ? 'results' : 'preview'))
  }

  return (
    <Screen title="Tìm bằng ảnh" onBack={goBackOrHome} testID="image-search-entry">
      <AppText variant="h2">Chụp hoặc chọn ảnh</AppText>
      <AppText variant="body">Ảnh rõ sản phẩm · tối đa 10 MB.</AppText>
      <View style={styles.frame}>
        <AppText variant="body" color="textSecondary">
          Đặt sản phẩm trong khung
        </AppText>
      </View>
      {CAMERA_AVAILABLE ? (
        <Button
          testID="image-search-open-camera"
          title="Cho phép camera và chụp"
          onPress={openCamera}
          loading={checking}
          disabled={busy}
        />
      ) : null}
      <Button
        testID="image-search-pick"
        title="Chọn ảnh từ thiết bị"
        kind={CAMERA_AVAILABLE ? 'secondary' : 'primary'}
        onPress={pickImage}
        loading={busy}
        disabled={checking}
      />
      {CAMERA_AVAILABLE ? (
        <AppText variant="caption" color="textSecondary">
          Camera chỉ được dùng khi bạn chọn chụp ảnh.
        </AppText>
      ) : null}
    </Screen>
  )
}

const styles = StyleSheet.create({
  frame: {
    height: 220,
    padding: 20,
    backgroundColor: colors.surfaceSubtle,
    borderWidth: 1,
    borderColor: colors.border,
  },
})
