import * as Location from 'expo-location'
import * as SecureStore from 'expo-secure-store'
import { sendLocation } from '../api/deliveryApi'
import {
  pushLocation,
  resetThrottleForTests,
  startTracking,
  stopTracking,
} from './locationTracking'

jest.mock('../api/deliveryApi', () => ({
  ...jest.requireActual('../api/deliveryApi'),
  sendLocation: jest.fn(),
}))

const coords = { latitude: 16.05, longitude: 108.2 }
const apiError = (status, code) =>
  Object.assign(new Error(code), { response: { status, data: { error: { code } } } })

beforeEach(() => {
  SecureStore.__store.clear()
  resetThrottleForTests()
  jest.clearAllMocks()
  sendLocation.mockResolvedValue({})
})

describe('startTracking', () => {
  it('uses the background task when "always" permission is granted', async () => {
    Location.requestBackgroundPermissionsAsync.mockResolvedValueOnce({ status: 'granted' })
    expect(await startTracking(['a1'])).toBe('background')
    expect(Location.startLocationUpdatesAsync).toHaveBeenCalledWith(
      'vmarket-shipper-location',
      expect.objectContaining({
        timeInterval: 15000,
        foregroundService: expect.objectContaining({ notificationTitle: expect.any(String) }),
      }),
    )
  })

  it('falls back to foreground-only tracking without background permission', async () => {
    expect(await startTracking(['a1'])).toBe('foreground')
    expect(Location.startLocationUpdatesAsync).not.toHaveBeenCalled()
  })

  it('reports a denied location permission', async () => {
    Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ status: 'denied' })
    expect(await startTracking(['a1'])).toBe('denied')
  })
})

describe('pushLocation', () => {
  it('sends one point to every moving trip and throttles below 10 seconds', async () => {
    await startTracking(['a1', 'a2'])
    expect(await pushLocation(coords, 100_000)).toEqual({ sent: true, error: undefined })
    expect(sendLocation).toHaveBeenCalledTimes(2)
    expect(sendLocation).toHaveBeenCalledWith('a1', coords)
    expect((await pushLocation(coords, 105_000)).sent).toBe(false)
    expect(sendLocation).toHaveBeenCalledTimes(2)
    await pushLocation(coords, 111_000)
    expect(sendLocation).toHaveBeenCalledTimes(4)
  })

  it('drops finished trips and stops once none is left', async () => {
    Location.hasStartedLocationUpdatesAsync.mockResolvedValue(true)
    await startTracking(['a1'])
    sendLocation.mockRejectedValueOnce(apiError(409, 'TRACKING_STOPPED'))
    await pushLocation(coords, 200_000)
    expect(SecureStore.__store.has('vmarket.trackingAssignments')).toBe(false)
    expect(Location.stopLocationUpdatesAsync).toHaveBeenCalledWith('vmarket-shipper-location')
    Location.hasStartedLocationUpdatesAsync.mockResolvedValue(false)
  })

  it('ignores the server rate limit but reports other errors', async () => {
    await startTracking(['a1'])
    sendLocation.mockRejectedValueOnce(apiError(429, 'PING_TOO_FREQUENT'))
    expect((await pushLocation(coords, 300_000)).error).toBeUndefined()
    sendLocation.mockRejectedValueOnce(apiError(500, 'BOOM'))
    expect((await pushLocation(coords, 320_000)).error).toBeTruthy()
  })

  it('does nothing after tracking stopped', async () => {
    await startTracking(['a1'])
    await stopTracking()
    expect((await pushLocation(coords, 400_000)).sent).toBe(false)
    expect(sendLocation).not.toHaveBeenCalled()
  })
})
