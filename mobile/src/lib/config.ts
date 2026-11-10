import Constants from 'expo-constants'
import { Platform } from 'react-native'

// API Gateway base URL.
// - EXPO_PUBLIC_API_BASE_URL wins when set (e.g. public demo gateway or LAN IP for a real phone).
// - Android emulator reaches the host machine through 10.0.2.2.
// - Web preview / iOS simulator use localhost.
function defaultBaseUrl() {
  if (Platform.OS === 'android') {
    const hostUri = Constants.expoConfig?.hostUri?.split(':')[0]
    // Expo Go on a physical device: reuse the Metro host IP.
    if (
      hostUri &&
      hostUri !== 'localhost' &&
      !hostUri.startsWith('127.') &&
      hostUri !== '10.0.2.2'
    ) {
      return `http://${hostUri}:8080`
    }
    return 'http://10.0.2.2:8080'
  }
  return 'http://localhost:8080'
}

export const API_BASE_URL = (process.env.EXPO_PUBLIC_API_BASE_URL || defaultBaseUrl()).replace(
  /\/$/,
  '',
)
