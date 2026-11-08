import * as Location from 'expo-location'
import { AppState, Platform } from 'react-native'

import { getApiError } from '@/api/client'
import { postLocation, type Assignment } from '@/api/delivery'
import { queryClient } from '@/lib/queryClient'

import { isTracking } from '../lib/status'

/**
 * Foreground GPS sharing for in-progress deliveries (PICKED_UP / IN_TRANSIT / RETURNING).
 *
 * Plain singleton so sharing survives screen changes inside the shipper area; `index.tsx` of the
 * area owns its lifecycle (`sync` on every list change, `stop` on unmount/logout). Uses only
 * foreground permission + timers, so it works in Expo Go but is suspended by the OS when the app
 * is backgrounded (see the report: background location needs a development build).
 */

const INTERVAL_MS = 15_000
const MAX_BACKOFF_MS = 60_000
const FIX_TIMEOUT_MS = 10_000
/** No successful ping for this long while tracking => "Mất kết nối GPS" (spec 3.22). */
const LOST_AFTER_MS = 120_000

export type TrackerStatus = 'idle' | 'sharing' | 'lost'
export type LostReason = 'services' | 'permission' | 'fix' | 'network' | null

export interface TrackerState {
  status: TrackerStatus
  lostReason: LostReason
  /** Last position successfully delivered to the server. */
  lastSent: { latitude: number; longitude: number; at: number } | null
  /** A fix waiting for connectivity (queue of one: only the newest position matters). */
  queued: boolean
}

const IDLE: TrackerState = { status: 'idle', lostReason: null, lastSent: null, queued: false }

type Point = { latitude: number; longitude: number }

let state: TrackerState = IDLE
let ids: string[] = []
let timer: ReturnType<typeof setTimeout> | null = null
let running = false
let failures = 0
let startedAt = 0
let queuedPoint: Point | null = null
/** Bumped by begin()/end(): results of a tick started in an older epoch are discarded. */
let epoch = 0
let appSub: { remove: () => void } | null = null
const listeners = new Set<() => void>()

function set(next: Partial<TrackerState>) {
  state = { ...state, ...next }
  listeners.forEach((l) => l())
}

export const trackerStore = {
  subscribe(l: () => void) {
    listeners.add(l)
    return () => {
      listeners.delete(l)
    }
  },
  getSnapshot: () => state,
}

function schedule(delay: number) {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void tick(), delay)
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout')), ms)
    p.then(
      (v) => {
        clearTimeout(t)
        resolve(v)
      },
      (e) => {
        clearTimeout(t)
        reject(e)
      },
    )
  })
}

async function readFix(): Promise<Point> {
  try {
    const fix = await withTimeout(
      Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }),
      FIX_TIMEOUT_MS,
    )
    return { latitude: fix.coords.latitude, longitude: fix.coords.longitude }
  } catch (e) {
    const last = await Location.getLastKnownPositionAsync({ maxAge: 60_000 })
    if (!last) throw e
    return { latitude: last.coords.latitude, longitude: last.coords.longitude }
  }
}

/** Posts to every tracked assignment. Returns true when the server was reached. */
async function send(point: Point): Promise<boolean> {
  let reached = false
  let stopped = false
  await Promise.all(
    ids.map(async (id) => {
      try {
        await postLocation(id, point)
        reached = true
      } catch (e) {
        const err = getApiError(e)
        // Only the deliberate answers count as "server reached": too frequent (429) and tracking
        // stopped (409). 401/403/5xx and transport errors are failures (backoff + lost logic).
        if (err.code === 'PING_TOO_FREQUENT' || err.code === 'TOO_FREQUENT') reached = true
        if (err.code === 'TRACKING_STOPPED') {
          reached = true
          stopped = true
        }
      }
    }),
  )
  if (stopped) void queryClient.invalidateQueries({ queryKey: ['shipper'] })
  return reached
}

function markLost(reason: LostReason) {
  set({ status: 'lost', lostReason: reason })
}

/** One cycle: permission -> fix -> post. Resolves true when the position reached the server. */
async function tick(): Promise<boolean> {
  if (running || ids.length === 0) return false
  running = true
  const myEpoch = epoch
  const stale = () => myEpoch !== epoch
  let ok = false
  try {
    const perm = await Location.getForegroundPermissionsAsync()
    if (stale()) return false
    if (!perm.granted) {
      markLost('permission')
      return false
    }
    const servicesOn = await Location.hasServicesEnabledAsync()
    if (stale()) return false
    if (!servicesOn) {
      markLost('services')
      return false
    }
    let point: Point | null = null
    try {
      point = await readFix()
      if (stale()) return false
      queuedPoint = point
    } catch {
      if (stale()) return false
      point = queuedPoint
      set({ queued: !!queuedPoint })
      if (!point) {
        failures += 1
        if (Date.now() - startedAt > LOST_AFTER_MS) markLost('fix')
        return false
      }
    }
    ok = await send(point)
    if (stale()) return false
    if (ok) {
      failures = 0
      startedAt = Date.now()
      queuedPoint = null
      set({
        status: 'sharing',
        lostReason: null,
        queued: false,
        lastSent: { ...point, at: Date.now() },
      })
    } else {
      failures += 1
      set({ queued: true })
      if (Date.now() - startedAt > LOST_AFTER_MS) markLost('network')
    }
    return ok
  } catch {
    if (!stale()) failures += 1
    return false
  } finally {
    running = false
    if (ids.length > 0) {
      // A stale tick must not delay the new run (its begin() tick was skipped while we ran).
      schedule(
        stale()
          ? 0
          : failures === 0
            ? INTERVAL_MS
            : Math.min(INTERVAL_MS * 2 ** failures, MAX_BACKOFF_MS),
      )
    }
  }
}

function begin() {
  epoch += 1
  failures = 0
  startedAt = Date.now()
  set({ lostReason: null })
  if (Platform.OS !== 'web' && !appSub) {
    appSub = AppState.addEventListener('change', (s) => {
      if (s === 'active' && ids.length > 0) schedule(0)
    })
  }
  schedule(0)
}

function end() {
  epoch += 1
  if (timer) clearTimeout(timer)
  timer = null
  appSub?.remove()
  appSub = null
  queuedPoint = null
  failures = 0
  state = IDLE
  listeners.forEach((l) => l())
}

export const tracker = {
  /** Replace the tracked set with the tracking-status assignments of `list`. Idempotent. */
  sync(list: Assignment[]) {
    const next = list.filter(isTracking).map((a) => a.id)
    const same = next.length === ids.length && next.every((id) => ids.includes(id))
    if (same) return
    const wasEmpty = ids.length === 0
    ids = next
    if (next.length === 0) end()
    else if (wasEmpty) begin()
  },
  stop() {
    ids = []
    end()
  },
  /** Manual "Thử kết nối lại": immediate cycle. */
  async retryNow(): Promise<boolean> {
    if (ids.length === 0) return false
    if (timer) clearTimeout(timer)
    failures = 0
    return tick()
  },
}
