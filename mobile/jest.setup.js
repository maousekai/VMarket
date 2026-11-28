// Module native khong chay trong Jest: thay bang ban gia trong bo nho.

jest.mock('expo-secure-store', () => {
  const store = new Map()
  return {
    AFTER_FIRST_UNLOCK: 'AFTER_FIRST_UNLOCK',
    getItemAsync: jest.fn(async (key) => (store.has(key) ? store.get(key) : null)),
    setItemAsync: jest.fn(async (key, value) => {
      store.set(key, value)
    }),
    deleteItemAsync: jest.fn(async (key) => {
      store.delete(key)
    }),
    __store: store,
  }
})

jest.mock('expo-task-manager', () => ({
  defineTask: jest.fn(),
  isAvailableAsync: jest.fn(async () => true),
}))

jest.mock('expo-location', () => ({
  Accuracy: { Balanced: 3, High: 4 },
  requestForegroundPermissionsAsync: jest.fn(async () => ({ status: 'granted' })),
  requestBackgroundPermissionsAsync: jest.fn(async () => ({ status: 'denied' })),
  getBackgroundPermissionsAsync: jest.fn(async () => ({ status: 'denied' })),
  hasServicesEnabledAsync: jest.fn(async () => true),
  hasStartedLocationUpdatesAsync: jest.fn(async () => false),
  startLocationUpdatesAsync: jest.fn(async () => {}),
  stopLocationUpdatesAsync: jest.fn(async () => {}),
  watchPositionAsync: jest.fn(async () => ({ remove: jest.fn() })),
}))

jest.mock('expo-keep-awake', () => ({
  activateKeepAwakeAsync: jest.fn(async () => {}),
  deactivateKeepAwake: jest.fn(async () => {}),
}))

jest.mock('expo-system-ui', () => ({ setBackgroundColorAsync: jest.fn(async () => {}) }))

jest.mock(
  'react-native-safe-area-context',
  () => jest.requireActual('react-native-safe-area-context/jest/mock').default,
)

// expo-router: dieu huong ghi lai de test kiem tra; useFocusEffect chay nhu useEffect.
jest.mock('expo-router', () => {
  const { useEffect } = jest.requireActual('react')
  const router = {
    push: jest.fn(),
    replace: jest.fn(),
    navigate: jest.fn(),
    back: jest.fn(),
    canGoBack: jest.fn(() => true),
  }
  return {
    router,
    useRouter: () => router,
    useLocalSearchParams: jest.fn(() => ({})),
    useFocusEffect: (effect) => useEffect(effect, [effect]),
    Link: ({ children }) => children,
  }
})
