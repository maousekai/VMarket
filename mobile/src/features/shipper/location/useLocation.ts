import * as Location from 'expo-location'
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { AppState, Linking } from 'react-native'

import type { Assignment } from '@/api/delivery'

import { tracker, trackerStore, type TrackerState } from './tracker'

export type PermissionState = 'loading' | 'undetermined' | 'granted' | 'denied'

/** Live tracker state (status, last delivered position, offline queue). */
export function useTrackerState(): TrackerState {
  return useSyncExternalStore(
    trackerStore.subscribe,
    trackerStore.getSnapshot,
    trackerStore.getSnapshot,
  )
}

/**
 * Owns the sharing lifecycle: call once from the area root screen with the full assignment list.
 * Starts/stops GPS pings as assignments enter/leave a tracking status and stops on unmount.
 */
export function useLocationSharing(assignments: Assignment[], enabled: boolean) {
  useEffect(() => {
    if (enabled) tracker.sync(assignments)
  }, [assignments, enabled])
  useEffect(() => () => tracker.stop(), [])
}

export function useLocationPermission() {
  const [state, setState] = useState<PermissionState>('loading')
  const [canAskAgain, setCanAskAgain] = useState(true)

  const apply = useCallback((p: Location.LocationPermissionResponse) => {
    setCanAskAgain(p.canAskAgain)
    setState(p.granted ? 'granted' : p.status === 'undetermined' ? 'undetermined' : 'denied')
    return p.granted
  }, [])

  const refresh = useCallback(
    async () => apply(await Location.getForegroundPermissionsAsync()),
    [apply],
  )

  const request = useCallback(async () => {
    const granted = apply(await Location.requestForegroundPermissionsAsync())
    if (granted) void tracker.retryNow()
    return granted
  }, [apply])

  useEffect(() => {
    let alive = true
    const check = () =>
      void Location.getForegroundPermissionsAsync().then((p) => {
        if (alive) apply(p)
      })
    check()
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active') check()
    })
    return () => {
      alive = false
      sub.remove()
    }
  }, [apply])

  return { state, canAskAgain, refresh, request, openSettings: () => Linking.openSettings() }
}
