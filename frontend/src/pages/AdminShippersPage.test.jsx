// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import AdminShippersPage from './AdminShippersPage'
import {
  assignShipper,
  createShipper,
  getDelivery,
  listAssignments,
  listShippers,
  setShipperLocked,
} from '../services/api/deliveryApi'

vi.mock('../services/api/deliveryApi', async (importOriginal) => ({
  ...(await importOriginal()),
  listAssignments: vi.fn(),
  listShippers: vi.fn(),
  createShipper: vi.fn(),
  setShipperLocked: vi.fn(),
  assignShipper: vi.fn(),
  getDelivery: vi.fn(),
  returnAssignment: vi.fn(),
  adminRetryAssignment: vi.fn(),
}))
vi.mock('../components/DeliveryTrackingPanel', () => ({ default: () => <div>bản đồ</div> }))

const shipper = {
  id: 's1',
  name: 'Trần Giao',
  phone: '0905000000',
  province: 'Đà Nẵng',
  district: null,
  active: true,
  total_orders: 10,
  delivered_orders: 9,
  success_rate_percent: 90,
}
const waiting = {
  id: 'a1',
  order_id: '01JORDER0000000000000ABCD1',
  shipper_id: null,
  status: 'WAITING_ASSIGNMENT',
  attempt: 1,
  province: 'Đà Nẵng',
  district: null,
  created_at: '2026-11-26T01:00:00Z',
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('AdminShippersPage', () => {
  it('assigns a waiting trip using the version from the delivery detail', async () => {
    listAssignments.mockResolvedValue([waiting])
    listShippers.mockResolvedValue([shipper, { ...shipper, id: 's2', active: false }])
    getDelivery.mockResolvedValue({ assignment: { version: 5 } })
    assignShipper.mockResolvedValue({})
    render(<AdminShippersPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Phân công' }))
    const dialog = within(screen.getByRole('dialog'))
    await waitFor(() => expect(dialog.getAllByRole('option')).toHaveLength(2))
    expect(listShippers).toHaveBeenCalledWith({ province: 'Đà Nẵng', size: 100 })
    fireEvent.change(dialog.getByLabelText(/Shipper/), { target: { value: 's1' } })
    fireEvent.click(dialog.getByRole('button', { name: 'Phân công' }))
    await waitFor(() =>
      expect(assignShipper).toHaveBeenCalledWith('a1', {
        shipperId: 's1',
        expectedVersion: 5,
        overrideArea: undefined,
        reason: undefined,
      }),
    )
    expect(await screen.findByText('Đã phân công shipper.')).toBeTruthy()
  })

  it('creates a shipper and reuses the idempotency key when the request is retried', async () => {
    listAssignments.mockResolvedValue([])
    listShippers.mockResolvedValue([])
    createShipper
      .mockRejectedValueOnce({
        response: { status: 503, data: { error: { message: 'Lỗi mạng' } } },
      })
      .mockResolvedValueOnce({ id: 's9', name: 'Lê Mới' })
    render(<AdminShippersPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Shipper' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Thêm shipper' }))
    const dialog = within(screen.getByRole('dialog'))
    const fill = (label, value) =>
      fireEvent.change(dialog.getByLabelText(label), { target: { value } })
    fill(/Họ tên/, 'Lê Mới')
    fill(/Số điện thoại/, '0905111222')
    fill(/^Email/, 'moi@vmarket.local')
    fill(/Tên đăng nhập/, 'le.moi')
    fill(/Mật khẩu tạm/, 'matkhau123')
    fill(/Tỉnh/, 'Đà Nẵng')
    fireEvent.click(dialog.getByRole('button', { name: 'Tạo shipper' }))
    expect(await dialog.findByText('Lỗi mạng')).toBeTruthy()
    fireEvent.click(dialog.getByRole('button', { name: 'Tạo shipper' }))
    expect(await screen.findByText('Đã tạo shipper Lê Mới.')).toBeTruthy()
    expect(createShipper).toHaveBeenCalledTimes(2)
    expect(createShipper.mock.calls[0][1]).toBe(createShipper.mock.calls[1][1])
    expect(createShipper.mock.calls[0][0]).toEqual({
      account: { email: 'moi@vmarket.local', username: 'le.moi', password: 'matkhau123' },
      name: 'Lê Mới',
      phone: '0905111222',
      province: 'Đà Nẵng',
      district: null,
    })
  })

  it('locks an active shipper', async () => {
    listAssignments.mockResolvedValue([])
    listShippers.mockResolvedValue([shipper])
    setShipperLocked.mockResolvedValue(undefined)
    render(<AdminShippersPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Shipper' }))
    expect(await screen.findByText('9 / 10')).toBeTruthy()
    expect(screen.getByText('90.0%')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Khoá' }))
    await waitFor(() => expect(setShipperLocked).toHaveBeenCalledWith('s1', true))
  })
})
