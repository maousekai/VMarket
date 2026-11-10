import {
  PlusJakartaSans_400Regular,
  PlusJakartaSans_500Medium,
  PlusJakartaSans_600SemiBold,
  PlusJakartaSans_700Bold,
  useFonts,
} from '@expo-google-fonts/plus-jakarta-sans'
import { QueryClientProvider } from '@tanstack/react-query'
import { Stack } from 'expo-router'
import * as SplashScreen from 'expo-splash-screen'
import { StatusBar } from 'expo-status-bar'
import { useEffect } from 'react'
import { GestureHandlerRootView } from 'react-native-gesture-handler'
import { SafeAreaProvider } from 'react-native-safe-area-context'

import { AuthProvider, useAuth } from '@/auth/AuthProvider'
import { ToastProvider } from '@/components/ui'
import { useNotificationDeepLinks } from '@/features/notifications'
import { queryClient } from '@/lib/queryClient'

void SplashScreen.preventAutoHideAsync()

function Gate({ fontsReady }: { fontsReady: boolean }) {
  const { status } = useAuth()
  useNotificationDeepLinks()
  const ready = fontsReady && status !== 'loading'
  useEffect(() => {
    if (ready) void SplashScreen.hideAsync()
  }, [ready])
  if (!ready) return null
  return (
    <>
      <StatusBar style="light" />
      <Stack screenOptions={{ headerShown: false }} />
    </>
  )
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    PlusJakartaSans_400Regular,
    PlusJakartaSans_500Medium,
    PlusJakartaSans_600SemiBold,
    PlusJakartaSans_700Bold,
  })
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <QueryClientProvider client={queryClient}>
          <AuthProvider>
            <ToastProvider>
              <Gate fontsReady={fontsLoaded || !!fontError} />
            </ToastProvider>
          </AuthProvider>
        </QueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  )
}
