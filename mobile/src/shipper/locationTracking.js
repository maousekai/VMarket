import * as Location from 'expo-location'
import * as SecureStore from 'expo-secure-store'
import * as TaskManager from 'expo-task-manager'
import { getApiError, onSessionChange } from '../api/client'
import { GPS_INTERVAL_MS, sendLocations } from '../api/deliveryApi'

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
const REVOKED_KEY = 'vmarket.revokedTrackingAssignments'
const BACKGROUND_ASKED_KEY = 'vmarket.backgroundPermissionAsked'
const STORE_OPTIONS = { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK }
// Server tu choi diem gui cach nhau duoi 10s; chan o client de khong ton request.
const MIN_GAP_MS = 10_000

let lastSentAt = 0
let trackingRevision = 0
let startRevision = 0
let storageUpdates = Promise.resolve()

function updateTrackingState(operation) {
  const next = storageUpdates.then(operation)
  storageUpdates = next.catch(() => {})
  return next
}

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

async function readRevoked() {
  return JSON.parse((await SecureStore.getItemAsync(REVOKED_KEY, STORE_OPTIONS)) || '{}')
}

async function writeRevoked(revoked) {
  if (Object.keys(revoked).length)
    await SecureStore.setItemAsync(REVOKED_KEY, JSON.stringify(revoked), STORE_OPTIONS)
  else await SecureStore.deleteItemAsync(REVOKED_KEY, STORE_OPTIONS)
}

export async function trackingAccess(assignmentIds) {
  const revoked = await readRevoked()
  const available = assignmentIds.filter((id) => !Object.hasOwn(revoked, id))
  const error = assignmentIds.map((id) => revoked[id]).find(Boolean)
  return { available, error, allRevoked: assignmentIds.length > 0 && !available.length }
}

/** Thoi diem server nhan diem GPS gan nhat (ca tu task nen), de UI chi bao "dang chia se" that. */
export async function readLastSentAt() {
  const value = Number(await SecureStore.getItemAsync(LAST_SENT_KEY, STORE_OPTIONS))
  return Number.isFinite(value) && value > 0 ? value : null
}

const CONFLICT = 'Một thiết bị hoặc trình duyệt khác đang chia sẻ vị trí cho chuyến này.'
const REVOKED = 'Bạn không còn quyền chia sẻ vị trí cho chuyến này.'
const ACCESS_ERRORS = new Set(['FORBIDDEN', 'SHIPPER_INACTIVE', 'ACCOUNT_SUSPENDED'])

/** Gửi một điểm cho các chuyến đang chạy qua API theo lô. */
export async function pushLocation(coords, now = Date.now()) {
  if (now - lastSentAt < MIN_GAP_MS) return { sent: false }
  const revision = trackingRevision
  const ids = await readIds()
  if (revision !== trackingRevision || now - lastSentAt < MIN_GAP_MS) return { sent: false }
  if (!ids.length) {
    await stopTracking()
    return { sent: false, stopped: true }
  }

  lastSentAt = now
  let outcomes
  let denial
  try {
    const response = await sendLocations(coords)
    outcomes = new Map(response.results.map(({ assignmentId, result }) => [assignmentId, result]))
  } catch (failure) {
    const error = getApiError(failure)
    if (revision !== trackingRevision) return { sent: false, stopped: true }
    if (error.status !== 403 && !ACCESS_ERRORS.has(error.code)) {
      return { sent: false, error: error.status === 429 ? undefined : error.message }
    }
    denial = error.message
    outcomes = new Map()
  }

  return updateTrackingState(async () => {
    if (revision !== trackingRevision) return { sent: false }
    const currentIds = await readIds()
    const dropped = new Set()
    const blocked = {}
    let error
    let sent = false
    // Preserve assignments added after the request started. The server omits trips that ended
    // or changed shipper and reports in-flight failures per assignment.
    for (const id of ids) {
      if (!currentIds.includes(id)) continue
      const result = outcomes.get(id)
      if (result === 'OK') sent = true
      else if (denial || result === 'GPS_SOURCE_CONFLICT' || ACCESS_ERRORS.has(result)) {
        error = denial || (result === 'GPS_SOURCE_CONFLICT' ? CONFLICT : REVOKED)
        blocked[id] = error
        dropped.add(id)
      } else if (!result || ['TRACKING_STOPPED', 'DELIVERY_NOT_FOUND'].includes(result)) {
        dropped.add(id)
      } else if (result !== 'PING_TOO_FREQUENT') {
        error = 'Không thể gửi vị trí cho một chuyến. Vui lòng thử lại.'
      }
    }
    if (sent) await SecureStore.setItemAsync(LAST_SENT_KEY, String(now), STORE_OPTIONS)
    if (dropped.size) {
      if (Object.keys(blocked).length) await writeRevoked({ ...(await readRevoked()), ...blocked })
      const remaining = currentIds.filter((id) => !dropped.has(id))
      if (!remaining.length) {
        trackingRevision += 1
        startRevision += 1
        await clearTracking(false)
        return { sent, error, stopped: true }
      }
      await writeIds(remaining)
    }
    return { sent, error }
  })
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
  if (!token) stopTracking({ clearRevoked: true }).catch(() => {})
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
    return false
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
      notificationBody: 'Vị trí chỉ được gửi trong lúc thực hiện chuyến giao.',
      notificationColor: '#0670CE',
    },
  })
}

/**
 * Bat dau / cap nhat danh sach chuyen can gui GPS.
 *
 * Quyen chay nen chi duoc XIN mot lan (Android mo thang trang Cai dat moi lan xin); cac lan sau
 * chi kiem tra. Shipper bat lai bang nut "Bat chia se khi chay nen" (requestBackgroundSharing).
 * @returns {Promise<'off'|'background'|'foreground'|'denied'|'servicesOff'|'revoked'>}
 */
export async function startTracking(assignmentIds) {
  if (!assignmentIds.length) {
    await stopTracking({ clearRevoked: true })
    return 'off'
  }
  const revision = trackingRevision
  const request = ++startRevision
  const current = () => request === startRevision && revision === trackingRevision
  await updateTrackingState(async () => {
    if (!current()) return
    const revoked = await readRevoked()
    await writeRevoked(
      Object.fromEntries(Object.entries(revoked).filter(([id]) => assignmentIds.includes(id))),
    )
  })
  if (!current()) return 'off'
  if ((await trackingAccess(assignmentIds)).allRevoked) {
    if (!current()) return 'off'
    await stopTracking()
    return 'revoked'
  }
  const foreground = await Location.requestForegroundPermissionsAsync()
  const enabled = foreground.status === 'granted' && (await servicesEnabled())
  if (!current()) return 'off'
  if (!enabled) {
    await stopTracking()
    return foreground.status !== 'granted' ? 'denied' : 'servicesOff'
  }
  const count = await updateTrackingState(async () => {
    if (!current()) return 0
    const { available } = await trackingAccess(assignmentIds)
    if (available.length) await writeIds(available)
    return available.length
  })
  if (!current()) return 'off'
  if (!count) return 'revoked'
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
  if (!current()) return 'off'
  if (background.status !== 'granted') return 'foreground'
  try {
    return await updateTrackingState(async () => {
      if (!current()) return 'off'
      if (!(await backgroundRunning())) await startBackgroundUpdates()
      return 'background'
    })
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
    return true
  } catch {
    return false
  }
}

export async function canShareInBackground() {
  return TaskManager.isAvailableAsync().catch(() => false)
}

async function clearTracking(clearRevoked) {
  await writeIds([])
  await SecureStore.deleteItemAsync(LAST_SENT_KEY, STORE_OPTIONS)
  if (clearRevoked) await writeRevoked({})
  if (await backgroundRunning()) {
    try {
      await Location.stopLocationUpdatesAsync(LOCATION_TASK)
    } catch {
      // Da dung.
    }
  }
}

export function stopTracking({ clearRevoked = false } = {}) {
  trackingRevision += 1
  startRevision += 1
  return updateTrackingState(() => clearTracking(clearRevoked))
}

/** Chi dung cho test. */
export function resetThrottleForTests() {
  lastSentAt = 0
}
