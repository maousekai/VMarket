import { useToast } from '@/components/ui'
import * as ImagePicker from 'expo-image-picker'
import { useCallback, useRef, useState } from 'react'

import { useImageSearch, type SelectedImage } from '@/features/imageSearch/ImageSearchContext'
import { prepareImage } from '@/features/imageSearch/imageFile'

/**
 * Turns a gallery pick or a camera photo into the shared `SelectedImage`.
 * On web the image picker opens the browser file chooser, so no camera is needed to test.
 */
export function useImageSource() {
  const { setImage } = useImageSearch()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)

  const accept = useCallback(
    async (source: { uri: string; width: number; height: number }) => {
      try {
        const image = await prepareImage(source)
        setImage(image)
        return image
      } catch {
        toast.show({
          type: 'error',
          message: 'Không đọc được ảnh. Hãy chọn ảnh JPEG, PNG hoặc WebP.',
        })
        return null
      }
    },
    [setImage, toast],
  )

  /** Runs `task` once at a time and exposes `busy` for button loading states. */
  const guarded = useCallback(async <T>(task: () => Promise<T | null>): Promise<T | null> => {
    if (lock.current) return null
    lock.current = true
    setBusy(true)
    try {
      return await task()
    } finally {
      lock.current = false
      setBusy(false)
    }
  }, [])

  const pickFromLibrary = useCallback(
    (): Promise<SelectedImage | null> =>
      guarded(async () => {
        const result = await ImagePicker.launchImageLibraryAsync({
          mediaTypes: ['images'],
          allowsEditing: false,
          quality: 1,
        })
        const asset = result.canceled ? null : result.assets[0]
        return asset ? accept(asset) : null
      }),
    [accept, guarded],
  )

  const acceptPhoto = useCallback(
    (capture: () => Promise<{ uri: string; width: number; height: number } | undefined>) =>
      guarded(async () => {
        const photo = await capture()
        return photo ? accept(photo) : null
      }),
    [accept, guarded],
  )

  return { busy, pickFromLibrary, acceptPhoto }
}
