import { useCameraPermissions } from 'expo-camera'
import { router } from 'expo-router'
import { useEffect, useState } from 'react'
import { AppState, Linking } from 'react-native'

import { AppText, Button, InfoCard, Screen } from '@/components/ui'
import { isTooLarge } from '@/features/imageSearch/imageFile'
import { href } from '@/features/imageSearch/navigation'
import { useImageSource } from '@/features/imageSearch/useImageSource'

/** Frames 3.40 (ask) and 3.41 (denied) - camera permission. */
export default function CameraPermissionScreen() {
  const [permission, requestPermission, getPermission] = useCameraPermissions()
  const { busy, pickFromLibrary } = useImageSource()
  const [working, setWorking] = useState(false)
  const denied = permission?.status === 'denied'

  useEffect(() => {
    if (permission?.granted) router.replace(href('camera'))
  }, [permission?.granted])

  // The user may have flipped the switch in system settings and come back.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') void getPermission()
    })
    return () => sub.remove()
  }, [getPermission])

  const pickImage = async () => {
    const image = await pickFromLibrary()
    if (image) router.replace(href(isTooLarge(image) ? 'results' : 'preview'))
  }

  const primary = async () => {
    if (working) return
    setWorking(true)
    try {
      if (!denied) {
        await requestPermission()
        return
      }
      const current = await getPermission()
      if (current.granted) return
      if (current.canAskAgain) await requestPermission()
      else await Linking.openSettings()
    } finally {
      setWorking(false)
    }
  }

  return (
    <Screen
      title={denied ? 'Camera chưa được phép' : 'Cho phép camera'}
      onBack={() => router.back()}
      testID="image-search-permission"
    >
      <AppText variant="body">
        {denied ? 'Bạn vẫn có thể dùng ảnh có sẵn' : 'Chụp sản phẩm để tìm ảnh tương đồng'}
      </AppText>
      <InfoCard
        title={denied ? 'Bật quyền camera' : 'Quyền camera'}
        body={
          denied
            ? 'Mở cài đặt thiết bị, cho phép camera rồi quay lại kiểm tra.'
            : 'VMarket dùng camera khi bạn chọn chụp ảnh. Bạn cũng có thể tải ảnh có sẵn, tối đa 10 MB.'
        }
      />
      <Button
        testID="image-search-permission-primary"
        title={denied ? 'Kiểm tra lại quyền' : 'Cho phép camera'}
        onPress={primary}
        loading={working}
        disabled={busy}
      />
      <Button
        testID="image-search-permission-pick"
        title="Chọn ảnh có sẵn"
        kind="secondary"
        onPress={pickImage}
        loading={busy}
        disabled={working}
      />
    </Screen>
  )
}
