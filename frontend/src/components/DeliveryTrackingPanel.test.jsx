// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import DeliveryTrackingPanel from './DeliveryTrackingPanel'

const WAITING_REFRESH_MS = 10_000
import { getDelivery, getRoute } from '../services/api/deliveryApi'
import useDeliveryTracking from '../hooks/useDeliveryTracking'

vi.mock('../services/api/deliveryApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getDelivery: vi.fn(),
  getRoute: vi.fn(),
}))
vi.mock('../hooks/useDeliveryTracking', () => ({
  default: vi.fn(() => ({ connection: 'idle', update: null })),
}))
vi.mock('./DeliveryMap', () => ({ default: () => <div data-testid="map" /> }))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.useRealTimers()
})

const detail = (status) => ({ assignment: { id: 'a1', status, version: 1 }, history: [] })

describe('DeliveryTrackingPanel', () => {
  it('switches to live tracking once the shipper picks up, without reloading the page', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    getRoute.mockResolvedValue([])
    getDelivery.mockResolvedValue(detail('ASSIGNED'))
    render(<DeliveryTrackingPanel orderId="o1" />)
    expect(await screen.findByText(/Bản đồ hiển thị khi shipper đã lấy hàng/)).toBeTruthy()
    expect(useDeliveryTracking).toHaveBeenLastCalledWith('o1', { enabled: false })

    getDelivery.mockResolvedValue(detail('PICKED_UP'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(WAITING_REFRESH_MS + 50)
    })
    expect(await screen.findByTestId('map')).toBeTruthy()
    expect(useDeliveryTracking).toHaveBeenLastCalledWith('o1', { enabled: true })
  })

  it('reloads when a notification arrives while waiting', async () => {
    getRoute.mockResolvedValue([])
    getDelivery.mockResolvedValue(detail('ASSIGNED'))
    render(<DeliveryTrackingPanel orderId="o1" />)
    await screen.findByText(/Bản đồ hiển thị khi shipper đã lấy hàng/)
    getDelivery.mockResolvedValue(detail('IN_TRANSIT'))
    await act(async () => {
      window.dispatchEvent(new Event('vmarket:notification-arrived'))
    })
    expect(await screen.findByTestId('map')).toBeTruthy()
  })
})
