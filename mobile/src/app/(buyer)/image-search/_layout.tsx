import { Stack } from 'expo-router'

import { ImageSearchProvider } from '@/features/imageSearch/ImageSearchContext'

// Deep links (e.g. /image-search/results) get the entry screen underneath so "back" works.
export const unstable_settings = { initialRouteName: 'index' }

/** Image search flow (UC-21). The chosen photo and filter live in the provider. */
export default function ImageSearchLayout() {
  return (
    <ImageSearchProvider>
      <Stack screenOptions={{ headerShown: false }} />
    </ImageSearchProvider>
  )
}
