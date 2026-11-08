import { CameraView, useCameraPermissions } from 'expo-camera'
import { Redirect, router, useFocusEffect } from 'expo-router'
import { useCallback, useRef, useState } from 'react'
import { StyleSheet, View } from 'react-native'

import { AppText, Button, Screen, Skeleton, useToast } from '@/components/ui'
import { href } from '@/features/imageSearch/navigation'
import { useImageSource } from '@/features/imageSearch/useImageSource'
import { colors, radius } from '@/theme'

/** Frame 3.42 - live viewfinder. */
export default function CameraScreen() {
  const [permission] = useCameraPermissions()
  const cameraRef = useRef<CameraView>(null)
  const [focused, setFocused] = useState(false)
  const [ready, setReady] = useState(false)
  const { busy, acceptPhoto } = useImageSource()
  const toast = useToast()

  // Release the camera whenever the screen is not in front.
  useFocusEffect(
    useCallback(() => {
      setFocused(true)
      return () => {
        setFocused(false)
        setReady(false)
      }
    }, []),
  )

  if (permission && !permission.granted) return <Redirect href={href('permission')} />

  const capture = async () => {
    try {
      const image = await acceptPhoto(
        async () => (await cameraRef.current?.takePictureAsync({ quality: 0.7 })) ?? undefined,
      )
      if (image) router.replace(href('preview'))
    } catch {
      toast.show({ type: 'error', message: 'Không chụp được ảnh. Vui lòng thử lại.' })
    }
  }

  return (
    <Screen title="Chụp ảnh sản phẩm" onBack={() => router.back()} testID="image-search-camera">
      <AppText variant="body">Đặt sản phẩm vào giữa khung hình</AppText>
      <View style={styles.viewfinder}>
        {!permission ? (
          <Skeleton height={300} radius={0} />
        ) : focused ? (
          <CameraView
            ref={cameraRef}
            style={StyleSheet.absoluteFill}
            facing="back"
            onCameraReady={() => setReady(true)}
            onMountError={() =>
              toast.show({ type: 'error', message: 'Không mở được camera. Hãy chọn ảnh có sẵn.' })
            }
          />
        ) : null}
      </View>
      <Button
        testID="image-search-capture"
        title="Chụp ảnh"
        onPress={capture}
        loading={busy}
        disabled={!ready}
      />
      <Button title="Quay lại" kind="secondary" onPress={() => router.back()} disabled={busy} />
    </Screen>
  )
}

const styles = StyleSheet.create({
  viewfinder: {
    height: 300,
    borderRadius: radius.md,
    overflow: 'hidden',
    backgroundColor: colors.text,
  },
})
