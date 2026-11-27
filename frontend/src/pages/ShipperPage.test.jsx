// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import ShipperPage from './ShipperPage'
import {
  listMyAssignments,
  retryAssignment,
  sendLocation,
  updateAssignmentStatus,
} from '../services/api/deliveryApi'

vi.mock('../services/api/deliveryApi', async (importOriginal) => ({
  ...(await importOriginal()),
  listMyAssignments: vi.fn(),
  updateAssignmentStatus: vi.fn(),
  retryAssignment: vi.fn(),
  sendLocation: vi.fn(),
}))
vi.mock('../components/DeliveryTrackingPanel', () => ({ default: () => <div>bản đồ</div> }))

const snapshot = JSON.stringify({
  recipientName: 'Nguyễn Văn An',
  phone: '0905123456',
  streetAddress: '1 Bạch Đằng',
  ward: 'Hải Châu 1',
  province: 'Đà Nẵng',
  items: [{ quantity: 2 }],
})
const trip = (overrides = {}) => ({
  id: 'a1',
  orderId: '01JORDER0000000000000ABCD1',
  status: 'ASSIGNED',
  attempt: 1,
  version: 3,
  paymentMethod: 'COD',
  codAmount: 150000,
  orderSnapshot: snapshot,
  createdAt: '2026-11-26T01:00:00Z',
  ...overrides,
})

let watchSuccess
const geolocation = {
  watchPosition: vi.fn((success) => {
    watchSuccess = success
    return 7
  }),
  clearWatch: vi.fn(),
  getCurrentPosition: vi.fn(),
}

beforeEach(() => {
  vi.stubGlobal('navigator', { ...navigator, geolocation })
  sendLocation.mockResolvedValue({})
})
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

const card = async () => (await screen.findByText(/Đơn #/)).closest('li')

describe('ShipperPage', () => {
  it('shows the recipient, address and COD of each assigned trip', async () => {
    listMyAssignments.mockResolvedValue([trip()])
    render(<ShipperPage />)
    const item = within(await card())
    expect(item.getByText(/Nguyễn Văn An/)).toBeTruthy()
    expect(item.getByRole('link', { name: '0905123456' }).getAttribute('href')).toBe(
      'tel:0905123456',
    )
    expect(item.getByText('1 Bạch Đằng, Hải Châu 1, Đà Nẵng')).toBeTruthy()
    expect(item.getByText(/COD/)).toBeTruthy()
  })

  it('advances the trip with the current version', async () => {
    listMyAssignments.mockResolvedValue([trip()])
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'PICKED_UP', version: 4 }))
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Đã lấy hàng' }))
    await screen.findByRole('button', { name: 'Bắt đầu giao' })
    expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
      status: 'PICKED_UP',
      expectedVersion: 3,
    })
  })

  it('requires the exact COD amount before confirming delivery', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'DELIVERED', version: 4 }))
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Giao thành công' }))
    const dialog = within(screen.getByRole('dialog'))
    const confirm = dialog.getByRole('button', { name: 'Xác nhận đã giao' })
    fireEvent.change(dialog.getByLabelText(/Số tiền đã thu/), { target: { value: '100000' } })
    expect(confirm.disabled).toBe(true)
    fireEvent.change(dialog.getByLabelText(/Số tiền đã thu/), { target: { value: '150000' } })
    fireEvent.click(confirm)
    await waitFor(() =>
      expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
        status: 'DELIVERED',
        expectedVersion: 3,
        codCollected: 150000,
      }),
    )
  })

  it('asks for a reason when the delivery fails', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'FAILED', version: 4 }))
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Giao thất bại' }))
    const dialog = within(screen.getByRole('dialog'))
    fireEvent.change(dialog.getByLabelText(/Lý do/), { target: { value: 'Không liên lạc được' } })
    fireEvent.click(dialog.getByRole('button', { name: 'Xác nhận thất bại' }))
    await waitFor(() =>
      expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
        status: 'FAILED',
        expectedVersion: 3,
        reason: 'Không liên lạc được',
      }),
    )
  })

  it('retries a failed trip', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'FAILED', attempt: 1 })])
    retryAssignment.mockResolvedValue(trip({ status: 'IN_TRANSIT', attempt: 2, version: 4 }))
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Giao lại' }))
    await waitFor(() => expect(retryAssignment).toHaveBeenCalledWith('a1', 3))
  })

  it('shares GPS only while the trip is moving and stops when it ends', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'DELIVERED', version: 4 }))
    render(<ShipperPage />)
    await card()
    expect(geolocation.watchPosition).toHaveBeenCalledTimes(1)
    watchSuccess({ coords: { latitude: 16.05, longitude: 108.2 }, timestamp: 1 })
    await waitFor(() =>
      expect(sendLocation).toHaveBeenCalledWith('a1', {
        latitude: 16.05,
        longitude: 108.2,
        timestamp: 1,
      }),
    )
    expect(await screen.findByText('Đang chia sẻ vị trí')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Giao thành công' }))
    const dialog = within(screen.getByRole('dialog'))
    fireEvent.change(dialog.getByLabelText(/Số tiền đã thu/), { target: { value: '150000' } })
    fireEvent.click(dialog.getByRole('button', { name: 'Xác nhận đã giao' }))
    await waitFor(() => expect(geolocation.clearWatch).toHaveBeenCalledWith(7))
  })

  it('does not ask for GPS before the parcel is picked up', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
    render(<ShipperPage />)
    await card()
    expect(geolocation.watchPosition).not.toHaveBeenCalled()
  })

  it('hides the buyer address and COD while returning the parcel', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'RETURNING' })])
    render(<ShipperPage />)
    const item = within(await card())
    expect(item.getByText(/Hoàn hàng về shop/)).toBeTruthy()
    expect(item.queryByText(/COD/)).toBeNull()
    expect(item.queryByText('1 Bạch Đằng, Hải Châu 1, Đà Nẵng')).toBeNull()
  })

  it('groups thousands in the COD amount field', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Giao thành công' }))
    const input = within(screen.getByRole('dialog')).getByLabelText(/Số tiền đã thu/)
    fireEvent.change(input, { target: { value: '150000' } })
    expect(input.value).toBe((150000).toLocaleString('vi-VN'))
  })

  it('keeps an unsent point after a 429 and warns instead of failing silently', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    sendLocation.mockRejectedValueOnce({
      response: { status: 429, data: { error: { code: 'PING_TOO_FREQUENT' } } },
    })
    render(<ShipperPage />)
    await card()
    watchSuccess({ coords: { latitude: 16.05, longitude: 108.2 }, timestamp: 1 })
    expect(await screen.findByText(/gửi chậm lại/)).toBeTruthy()
  })

  it('stops watching the position when the server revokes access (403)', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    sendLocation.mockRejectedValueOnce({
      response: { status: 403, data: { error: { code: 'FORBIDDEN', message: 'Không có quyền' } } },
    })
    render(<ShipperPage />)
    await card()
    watchSuccess({ coords: { latitude: 16.05, longitude: 108.2 }, timestamp: 1 })
    await waitFor(() => expect(geolocation.clearWatch).toHaveBeenCalledWith(7))
  })
})
