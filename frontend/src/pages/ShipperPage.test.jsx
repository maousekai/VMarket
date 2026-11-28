// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import ShipperPage from './ShipperPage'
import {
  confirmPickup,
  confirmReturn,
  listMyAssignments,
  retryAssignment,
  sendLocations,
  updateAssignmentStatus,
} from '../services/api/deliveryApi'

vi.mock('../services/api/deliveryApi', async (importOriginal) => ({
  ...(await importOriginal()),
  listMyAssignments: vi.fn(),
  updateAssignmentStatus: vi.fn(),
  retryAssignment: vi.fn(),
  confirmPickup: vi.fn(),
  confirmReturn: vi.fn(),
  sendLocations: vi.fn(),
}))
vi.mock('../components/DeliveryTrackingPanel', () => ({ default: () => <div>bản đồ</div> }))

const trip = (overrides = {}) => ({
  id: 'a1',
  orderId: '01JORDER0000000000000ABCD1',
  status: 'ASSIGNED',
  attempt: 1,
  maxAttempts: 3,
  version: 3,
  paymentMethod: 'COD',
  codAmount: 150000,
  createdAt: '2026-11-26T01:00:00Z',
  ward: 'Phường Hòa Cường',
  zone: 'Hải Châu',
  itemCount: 2,
  recipient: {
    name: 'Nguyễn Văn An',
    phone: '0905123456',
    address: '1 Bạch Đằng, Phường Hòa Cường, Đà Nẵng',
    note: '',
    location: { latitude: 16.05, longitude: 108.2 },
  },
  pickups: [
    {
      shop_id: 'shop-1',
      name: 'Gốm Hội An',
      phone: '0901111222',
      address: '9 Lê Duẩn, Đà Nẵng',
      picked_up_at: null,
      returned_at: null,
    },
    {
      shop_id: 'shop-2',
      name: 'Áo Dài Huế',
      phone: '0903333444',
      address: '3 Hùng Vương, Đà Nẵng',
      picked_up_at: null,
      returned_at: null,
    },
  ],
  failures: [],
  earliestRetryAt: null,
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
  sendLocations.mockResolvedValue({ results: [{ assignmentId: 'a1', result: 'OK' }] })
  listMyAssignments.mockImplementation(async ({ scope }) => (scope === 'active' ? [trip()] : []))
})
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

const card = async () => (await screen.findByText(/Đơn #/)).closest('li')
const active = (overrides) =>
  listMyAssignments.mockImplementation(async ({ scope }) =>
    scope === 'active' ? [trip(overrides)] : [],
  )

describe('ShipperPage', () => {
  it('loads every running trip with the active scope and shows recipient, zone and COD', async () => {
    render(<ShipperPage />)
    const item = within(await card())
    expect(listMyAssignments).toHaveBeenCalledWith({ scope: 'active' })
    expect(item.getByText('Nguyễn Văn An')).toBeTruthy()
    expect(item.getByRole('link', { name: 'Gọi 0905123456' }).getAttribute('href')).toBe(
      'tel:0905123456',
    )
    expect(item.getAllByRole('link', { name: 'Chỉ đường' })[0].getAttribute('href')).toContain(
      'destination=16.05%2C108.2',
    )
    expect(item.getByText('Phường Hòa Cường, khu Hải Châu')).toBeTruthy()
    expect(item.getByText(/COD/)).toBeTruthy()
  })

  it('confirms pickup per shop with the current version', async () => {
    confirmPickup.mockResolvedValue(
      trip({
        version: 4,
        pickups: [
          { ...trip().pickups[0], picked_up_at: '2026-11-26T02:00:00Z' },
          trip().pickups[1],
        ],
      }),
    )
    render(<ShipperPage />)
    const item = within(await card())
    expect(item.getByText('Gốm Hội An')).toBeTruthy()
    expect(item.getByText('Áo Dài Huế')).toBeTruthy()
    fireEvent.click(item.getAllByRole('button', { name: 'Đã lấy hàng ở shop này' })[0])
    await waitFor(() => expect(confirmPickup).toHaveBeenCalledWith('a1', 'shop-1', 3))
    expect(await item.findByText(/Đã lấy ·/)).toBeTruthy()
    expect(item.getAllByRole('button', { name: 'Đã lấy hàng ở shop này' })).toHaveLength(1)
  })

  it('reports failure with a standard reason, OTHER needs a note, refusal warns about return', async () => {
    active({ status: 'IN_TRANSIT' })
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'FAILED', version: 4 }))
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Giao thất bại' }))
    const dialog = within(screen.getByRole('dialog'))
    const submit = dialog.getByRole('button', { name: 'Xác nhận thất bại' })
    expect(submit.disabled).toBe(true)
    fireEvent.change(dialog.getByLabelText(/^Lý do/), { target: { value: 'OTHER' } })
    expect(submit.disabled).toBe(true)
    fireEvent.change(dialog.getByLabelText(/Ghi chú/), { target: { value: 'Cổng khoá' } })
    expect(submit.disabled).toBe(false)
    fireEvent.change(dialog.getByLabelText(/^Lý do/), { target: { value: 'CUSTOMER_REFUSED' } })
    expect(dialog.getByText(/chuyển sang hoàn hàng về shop ngay/)).toBeTruthy()
    fireEvent.change(dialog.getByLabelText(/^Lý do/), {
      target: { value: 'CUSTOMER_UNREACHABLE' },
    })
    fireEvent.click(submit)
    await waitFor(() =>
      expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
        status: 'FAILED',
        expectedVersion: 3,
        reasonCode: 'CUSTOMER_UNREACHABLE',
        reason: 'Cổng khoá',
      }),
    )
  })

  it('locks retry until the earliest retry time and lists earlier failures', async () => {
    const later = new Date(Date.now() + 60 * 60 * 1000).toISOString()
    active({
      status: 'FAILED',
      earliestRetryAt: later,
      failures: [{ attempt: 1, public_reason: 'Khách hẹn giao lại', occurred_at: later }],
    })
    render(<ShipperPage />)
    const item = within(await card())
    expect(item.getByText(/Lần 1: Khách hẹn giao lại/)).toBeTruthy()
    const retry = item.getByRole('button', { name: /Giao lại từ/ })
    expect(retry.disabled).toBe(true)
  })

  it('retries once the waiting time has passed', async () => {
    active({ status: 'FAILED', earliestRetryAt: '2020-01-01T00:00:00Z' })
    retryAssignment.mockResolvedValue(trip({ status: 'IN_TRANSIT', attempt: 2, version: 4 }))
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Giao lại' }))
    await waitFor(() => expect(retryAssignment).toHaveBeenCalledWith('a1', 3))
  })

  it('returns the parcel shop by shop and hides the buyer and COD', async () => {
    active({ status: 'RETURNING', codAmount: 0, recipient: null })
    confirmReturn.mockResolvedValue(trip({ status: 'RETURN_DROPPED_OFF', version: 4 }))
    render(<ShipperPage />)
    const item = within(await card())
    expect(item.getByText(/Hoàn hàng về shop/)).toBeTruthy()
    expect(item.queryByText(/COD/)).toBeNull()
    expect(item.queryByText('Nguyễn Văn An')).toBeNull()
    fireEvent.click(item.getAllByRole('button', { name: 'Đã trả hàng cho shop này' })[0])
    await waitFor(() => expect(confirmReturn).toHaveBeenCalledWith('a1', 'shop-1', 3))
  })

  it('requires the exact COD amount, grouped by thousands', async () => {
    active({ status: 'IN_TRANSIT' })
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'DELIVERED', version: 4 }))
    render(<ShipperPage />)
    fireEvent.click(within(await card()).getByRole('button', { name: 'Giao thành công' }))
    const dialog = within(screen.getByRole('dialog'))
    const input = dialog.getByLabelText(/Số tiền đã thu/)
    fireEvent.change(input, { target: { value: '100000' } })
    expect(dialog.getByRole('button', { name: 'Xác nhận đã giao' }).disabled).toBe(true)
    fireEvent.change(input, { target: { value: '150000' } })
    expect(input.value).toBe((150000).toLocaleString('vi-VN'))
    fireEvent.click(dialog.getByRole('button', { name: 'Xác nhận đã giao' }))
    await waitFor(() =>
      expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
        status: 'DELIVERED',
        expectedVersion: 3,
        codCollected: 150000,
      }),
    )
  })

  it('does not send GPS from the browser until the shipper turns it on', async () => {
    active({ status: 'IN_TRANSIT' })
    render(<ShipperPage />)
    await card()
    expect(geolocation.watchPosition).not.toHaveBeenCalled()
    expect(screen.getByText('Vị trí đang được gửi từ ứng dụng di động')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Chia sẻ từ trình duyệt này' }))
    expect(geolocation.watchPosition).toHaveBeenCalledTimes(1)
    // Chua co diem nao duoc server nhan thi chua bao "dang chia se".
    expect(screen.getByText('Đang chờ máy chủ nhận vị trí…')).toBeTruthy()
    watchSuccess({ coords: { latitude: 16.05, longitude: 108.2 }, timestamp: 1 })
    await waitFor(() =>
      expect(sendLocations).toHaveBeenCalledWith({
        latitude: 16.05,
        longitude: 108.2,
        timestamp: 1,
      }),
    )
    expect(await screen.findByText('Trình duyệt này đang chia sẻ vị trí')).toBeTruthy()
  })

  it('explains when the mobile app already sends GPS for the trip', async () => {
    active({ status: 'IN_TRANSIT' })
    sendLocations.mockResolvedValue({
      results: [{ assignmentId: 'a1', result: 'GPS_SOURCE_CONFLICT' }],
    })
    render(<ShipperPage />)
    await card()
    fireEvent.click(screen.getByRole('button', { name: 'Chia sẻ từ trình duyệt này' }))
    watchSuccess({ coords: { latitude: 16.05, longitude: 108.2 }, timestamp: 1 })
    expect(await screen.findByText(/Ứng dụng di động đang gửi vị trí/)).toBeTruthy()
  })

  it('stops watching the position when the server revokes access (403)', async () => {
    active({ status: 'IN_TRANSIT' })
    sendLocations.mockRejectedValue({ response: { status: 403, data: {} } })
    render(<ShipperPage />)
    await card()
    fireEvent.click(screen.getByRole('button', { name: 'Chia sẻ từ trình duyệt này' }))
    watchSuccess({ coords: { latitude: 16.05, longitude: 108.2 }, timestamp: 1 })
    await waitFor(() => expect(geolocation.clearWatch).toHaveBeenCalledWith(7))
    expect(await screen.findByText(/không còn quyền gửi vị trí/)).toBeTruthy()
  })

  it('pages finished trips separately', async () => {
    listMyAssignments.mockImplementation(async ({ scope }) =>
      scope === 'finished'
        ? [trip({ id: 'f1', status: 'DELIVERED', completedAt: '2026-11-26T05:00:00Z' })]
        : [],
    )
    render(<ShipperPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Đã kết thúc' }))
    expect(await screen.findByText(/kết thúc/)).toBeTruthy()
    expect(listMyAssignments).toHaveBeenCalledWith({ scope: 'finished', page: 0, size: 20 })
  })
})
