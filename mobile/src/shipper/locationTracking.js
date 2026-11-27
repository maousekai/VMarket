import * as Location from 'expo-location'
import * as SecureStore from 'expo-secure-store'
import * as TaskManager from 'expo-task-manager'
import { getApiError, onSessionChange } from '../api/client'
import { GPS_INTERVAL_MS, sendLocation } from '../api/deliveryApi'

/**
 * Gui GPS cua shipper (FR-SHIP-04): moi GPS_INTERVAL_MS (15s, trong khoang 10-30s) mot diem cho
 * moi chuyen dang chay (PICKED_UP / IN_TRANSIT / RETURNING); tu dung khi khong con chuyen nao.
 *
 * - Co quyen "Luon cho phep" + ban build that: dung background task (Android foreground service
 *   co thong bao thuong truc) nen van gui khi app chay nen / tat man hinh.
 * - Khong co quyen nen (hoac Expo Go): chi gui khi app dang mo (watchPositionAsync).
 *
 * NFR-SEC-07: chi thu thap vi tri khi dang thuc hien chuyen. Dung ngay khi server tu choi
 * (403 bi khoa / doi shipper, 409 chuyen ket thuc, 404) va khi phien dang nhap ket thuc.
 *
 * defineTask PHAI chay o module scope -> file nay duoc import o src/app/_layout.jsx.
 */
export const LOCATION_TASK = 'vmarket-shipper-location'
const IDS_KEY = 'vmarket.trackingAssignments'
const LAST_SENT_KEY = 'vmarket.lastLocationSentAt'
const BACKGROUND_ASKED_KEY = 'vmarket.backgroundPermissionAsked'
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

/** Thoi diem server nhan diem GPS gan nhat (ca tu task nen), de UI chi bao "dang chia se" that. */
export async function readLastSentAt() {
  const value = Number(await SecureStore.getItemAsync(LAST_SENT_KEY, STORE_OPTIONS))
  return Number.isFinite(value) && value > 0 ? value : null
}

/** Ma loi nghia la chuyen khong con nhan GPS tu shipper nay: bo khoi danh sach theo doi. */
const stopsTracking = (apiError) =>
  apiError.code === 'TRACKING_STOPPED' || apiError.status === 404 || apiError.status === 403

/**
 * Gui mot diem toi moi chuyen dang theo doi. Chuyen bi tu choi (xem stopsTracking) bi bo khoi
 * danh sach; het chuyen thi dung han viec lay vi tri (ke ca task nen va thong bao thuong truc).
 * @returns {Promise<{sent: boolean, error?: string, stopped?: boolean}>}
 */
export async function pushLocation(coords, now = Date.now()) {
  if (now - lastSentAt < MIN_GAP_MS) return { sent: false }
  const ids = await readIds()
  if (!ids.length) {
    await stopTracking()
    return { sent: false, stopped: true }
  }
  lastSentAt = now
  const point = { latitude: coords.latitude, longitude: coords.longitude }
  const results = await Promise.allSettled(ids.map((id) => sendLocation(id, point)))
  const dropped = []
  let error
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') return
    const apiError = getApiError(result.reason)
    if (stopsTracking(apiError)) {
      dropped.push(ids[index])
      if (apiError.status === 403) error = apiError.message
    } else if (apiError.status !== 429) error = apiError.message
  })
  const sent = results.some((result) => result.status === 'fulfilled')
  if (sent) await SecureStore.setItemAsync(LAST_SENT_KEY, String(now), STORE_OPTIONS)
  if (dropped.length) {
    const remaining = ids.filter((id) => !dropped.includes(id))
    await writeIds(remaining)
    if (!remaining.length) {
      await stopTracking()
      return { sent, error, stopped: true }
    }
  }
  return { sent, error }
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

// Phien ket thuc vi bat ky ly do gi (dang xuat, refresh token het han, ACCOUNT_SUSPENDED): dung
// GPS ngay tai mot cho duy nhat (review PR 69, NFR-SEC-07).
onSessionChange((token) => {
  if (!token) stopTracking().catch(() => {})
})

async function backgroundRunning() {
  try {
    return await Location.hasStartedLocationUpdatesAsync(LOCATION_TASK)
  } catch {
    return false
  }
}

async function servicesEnabled() {
  try {
    return await Location.hasServicesEnabledAsync()
  } catch {
    return true
  }
}

async function startBackgroundUpdates() {
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
}

/**
 * Bat dau / cap nhat danh sach chuyen can gui GPS.
 *
 * Quyen chay nen chi duoc XIN mot lan (Android mo thang trang Cai dat moi lan xin); cac lan sau
 * chi kiem tra. Shipper bat lai bang nut "Bat chia se khi chay nen" (requestBackgroundSharing).
 * @returns {Promise<'background'|'foreground'|'denied'|'servicesOff'>}
 */
export async function startTracking(assignmentIds) {
  await writeIds(assignmentIds)
  const foreground = await Location.requestForegroundPermissionsAsync()
  if (foreground.status !== 'granted') return 'denied'
  if (!(await servicesEnabled())) return 'servicesOff'
  if (await backgroundRunning()) return 'background'
  let background = { status: 'denied' }
  try {
    if (await TaskManager.isAvailableAsync()) {
      const asked = await SecureStore.getItemAsync(BACKGROUND_ASKED_KEY, STORE_OPTIONS)
      if (asked) background = await Location.getBackgroundPermissionsAsync()
      else {
        await SecureStore.setItemAsync(BACKGROUND_ASKED_KEY, '1', STORE_OPTIONS)
        background = await Location.requestBackgroundPermissionsAsync()
      }
    }
  } catch {
    background = { status: 'denied' }
  }
  if (background.status !== 'granted') return 'foreground'
  try {
    await startBackgroundUpdates()
    return 'background'
  } catch {
    // Expo Go / thieu cau hinh native: van gui khi app dang mo.
    return 'foreground'
  }
}

/** Shipper chu dong bat chia se khi chay nen (nut trong banner). */
export async function requestBackgroundSharing() {
  try {
    if (!(await TaskManager.isAvailableAsync())) return false
    const result = await Location.requestBackgroundPermissionsAsync()
    if (result.status !== 'granted') return false
    if (!(await backgroundRunning())) await startBackgroundUpdates()
    return true
  } catch {
    return false
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
