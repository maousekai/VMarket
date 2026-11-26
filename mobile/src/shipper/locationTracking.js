import * as Location from 'expo-location'
import * as SecureStore from 'expo-secure-store'
import * as TaskManager from 'expo-task-manager'
import { getApiError } from '../api/client'
import { GPS_INTERVAL_MS, sendLocation } from '../api/deliveryApi'

/**
 * Gui GPS cua shipper (FR-SHIP-04): moi GPS_INTERVAL_MS (15s, trong khoang 10-30s) mot diem cho
 * moi chuyen dang chay (PICKED_UP / IN_TRANSIT / RETURNING); tu dung khi khong con chuyen nao.
 *
 * - Co quyen "Luon cho phep" + ban build that: dung background task (Android foreground service
 *   co thong bao thuong truc) nen van gui khi app chay nen / tat man hinh.
 * - Khong co quyen nen (hoac Expo Go): chi gui khi app dang mo (watchPositionAsync).
 *
 * defineTask PHAI chay o module scope -> file nay duoc import o src/app/_layout.jsx.
 */
export const LOCATION_TASK = 'vmarket-shipper-location'
const IDS_KEY = 'vmarket.trackingAssignments'
const STORE_OPTIONS = { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK }
// Server tu choi diem gui cach nhau duoi 10s; chan o client de khong ton request.
const MIN_GAP_MS = 10_000

let lastSentAt = 0

async function readIds() {
  try {
    return JSON.parse((await SecureStore.getItemAsync(IDS_KEY, STORE_OPTIONS)) || '[]')
  } catch {
    return []
  }
}

async function writeIds(ids) {
  if (ids.length) await SecureStore.setItemAsync(IDS_KEY, JSON.stringify(ids), STORE_OPTIONS)
  else await SecureStore.deleteItemAsync(IDS_KEY, STORE_OPTIONS)
}

/**
 * Gui mot diem toi moi chuyen dang theo doi. Chuyen da ket thuc (409 TRACKING_STOPPED) bi bo khoi
 * danh sach; het chuyen thi dung han viec lay vi tri.
 * @returns {Promise<{sent: boolean, error?: string}>}
 */
export async function pushLocation(coords, now = Date.now()) {
  if (now - lastSentAt < MIN_GAP_MS) return { sent: false }
  const ids = await readIds()
  if (!ids.length) {
    await stopTracking()
    return { sent: false }
  }
  lastSentAt = now
  const point = { latitude: coords.latitude, longitude: coords.longitude }
  const results = await Promise.allSettled(ids.map((id) => sendLocation(id, point)))
  const stopped = []
  let error
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') return
    const apiError = getApiError(result.reason)
    if (apiError.code === 'TRACKING_STOPPED' || apiError.status === 404) stopped.push(ids[index])
    else if (apiError.status !== 429) error = apiError.message
  })
  if (stopped.length) {
    const remaining = ids.filter((id) => !stopped.includes(id))
    await writeIds(remaining)
    if (!remaining.length) await stopTracking()
  }
  return { sent: results.some((result) => result.status === 'fulfilled'), error }
}

TaskManager.defineTask(LOCATION_TASK, async ({ data, error }) => {
  if (error || !data?.locations?.length) return
  const latest = data.locations[data.locations.length - 1]
  try {
    await pushLocation(latest.coords)
  } catch {
    // Task nen khong duoc nem loi; lan sau thu lai.
  }
})

async function backgroundRunning() {
  try {
    return await Location.hasStartedLocationUpdatesAsync(LOCATION_TASK)
  } catch {
    return false
  }
}

/**
 * Bat dau / cap nhat danh sach chuyen can gui GPS.
 * @returns {Promise<'background'|'foreground'|'denied'>}
 */
export async function startTracking(assignmentIds) {
  await writeIds(assignmentIds)
  const foreground = await Location.requestForegroundPermissionsAsync()
  if (foreground.status !== 'granted') return 'denied'
  if (await backgroundRunning()) return 'background'
  let background = { status: 'denied' }
  try {
    if (await TaskManager.isAvailableAsync())
      background = await Location.requestBackgroundPermissionsAsync()
  } catch {
    background = { status: 'denied' }
  }
  if (background.status !== 'granted') return 'foreground'
  try {
    await Location.startLocationUpdatesAsync(LOCATION_TASK, {
      accuracy: Location.Accuracy.High,
      timeInterval: GPS_INTERVAL_MS,
      distanceInterval: 0,
      pausesUpdatesAutomatically: false,
      showsBackgroundLocationIndicator: true,
      foregroundService: {
        notificationTitle: 'VMarket đang chia sẻ vị trí',
        notificationBody: 'Người mua đang theo dõi đơn bạn giao. Tự tắt khi giao xong.',
        notificationColor: '#0670CE',
      },
    })
    return 'background'
  } catch {
    // Expo Go / thieu cau hinh native: van gui khi app dang mo.
    return 'foreground'
  }
}

export async function stopTracking() {
  await writeIds([])
  if (await backgroundRunning()) {
    try {
      await Location.stopLocationUpdatesAsync(LOCATION_TASK)
    } catch {
      // Da dung.
    }
  }
}

/** Chi dung cho test. */
export function resetThrottleForTests() {
  lastSentAt = 0
}
