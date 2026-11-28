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

// API tra khu vuc da chuan hoa (khong dau); UI hien lai ten co dau.
const shipper = (overrides = {}) => ({
  id: 's1',
  name: 'Trần Giao',
  phone: '0905000000',
  province: 'da nang',
  district: 'hai chau',
  active: true,
  account_status: 'ACTIVE',
  total_orders: 10,
  active_orders: 2,
  delivered_orders: 7,
  success_rate_percent: 87.5,
  ...overrides,
})
const waiting = {
  id: 'a1',
  order_id: '01JORDER0000000000000ABCD1',
  shipper_id: null,
  shipper_name: null,
  status: 'WAITING_ASSIGNMENT',
  attempt: 1,
  version: 5,
  province: 'da nang',
  district: 'hai chau',
  created_at: '2026-11-26T01:00:00Z',
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

const mockAssignments = (rows) =>
  listAssignments.mockImplementation(async ({ status }) =>
    rows.filter((row) => !status || row.status === status),
  )

describe('AdminShippersPage dispatch', () => {
  it('shows shipper names, accented areas and flags trips waiting for a shipper', async () => {
    mockAssignments([
      waiting,
      { ...waiting, id: 'a2', status: 'IN_TRANSIT', shipper_id: 's1', shipper_name: 'Trần Giao' },
    ])
    render(<AdminShippersPage />)
    const table = await screen.findByRole('table', { name: 'Danh sách chuyến giao' })
    expect(within(table).getByText('Trần Giao')).toBeTruthy()
    expect(within(table).getAllByText('khu Hải Châu, Đà Nẵng')).toHaveLength(2)
    expect(within(table).getByText('Cần phân công')).toBeTruthy()
    expect(await screen.findByText(/1 chuyến chưa có shipper/)).toBeTruthy()
  })

  it('filters trips by status on the server', async () => {
    mockAssignments([waiting])
    render(<AdminShippersPage />)
    await screen.findByRole('table', { name: 'Danh sách chuyến giao' })
    fireEvent.change(screen.getByLabelText('Trạng thái'), { target: { value: 'FAILED' } })
    await waitFor(() =>
      expect(listAssignments).toHaveBeenCalledWith({ status: 'FAILED', page: 0, size: 20 }),
    )
  })

  it('offers only active shippers, tags other districts and uses the listed version', async () => {
    mockAssignments([waiting])
    listShippers.mockResolvedValue([
      shipper(),
      shipper({ id: 's2', name: 'Lê Sơn Trà', district: 'son tra' }),
      shipper({ id: 's3', name: 'Chờ Kích Hoạt', account_status: 'PENDING_ACTIVATION' }),
      shipper({ id: 's4', name: 'Bị Khoá', active: false, account_status: 'LOCKED' }),
    ])
    assignShipper.mockResolvedValue({})
    render(<AdminShippersPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Phân công' }))
    const dialog = within(screen.getByRole('dialog'))
    await waitFor(() => expect(dialog.getAllByRole('option')).toHaveLength(3))
    expect(listShippers).toHaveBeenCalledWith({ province: 'da nang', size: 100 })
    expect(getDelivery).not.toHaveBeenCalled()
    expect(dialog.getByRole('option', { name: /Trần Giao · Hải Châu · 2 chuyến/ })).toBeTruthy()
    expect(dialog.getByRole('option', { name: /Lê Sơn Trà.*Khác khu/ })).toBeTruthy()
    expect(dialog.queryByRole('option', { name: /Chờ Kích Hoạt|Bị Khoá/ })).toBeNull()

    fireEvent.change(dialog.getByLabelText(/^Shipper/), { target: { value: 's1' } })
    expect(dialog.queryByLabelText(/Lý do/)).toBeNull()
    fireEvent.click(dialog.getByRole('button', { name: 'Phân công' }))
    await waitFor(() =>
      expect(assignShipper).toHaveBeenCalledWith('a1', {
        shipperId: 's1',
        expectedVersion: 5,
        overrideArea: undefined,
        reason: undefined,
      }),
    )
  })

  it('requires an internal reason and sends the override for another district', async () => {
    mockAssignments([waiting])
    listShippers.mockResolvedValue([shipper({ id: 's2', name: 'Lê Sơn Trà', district: 'son tra' })])
    assignShipper.mockResolvedValue({})
    render(<AdminShippersPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Phân công' }))
    const dialog = within(screen.getByRole('dialog'))
    await waitFor(() => expect(dialog.getAllByRole('option')).toHaveLength(2))
    fireEvent.change(dialog.getByLabelText(/^Shipper/), { target: { value: 's2' } })
    const submit = dialog.getByRole('button', { name: 'Phân công' })
    expect(submit.disabled).toBe(true)
    fireEvent.change(dialog.getByLabelText(/Lý do/), { target: { value: 'Hải Châu hết người' } })
    fireEvent.click(submit)
    await waitFor(() =>
      expect(assignShipper).toHaveBeenCalledWith('a1', {
        shipperId: 's2',
        expectedVersion: 5,
        overrideArea: true,
        reason: 'Hải Châu hết người',
      }),
    )
  })
})

describe('AdminShippersPage shippers', () => {
  async function openShippers(rows) {
    mockAssignments([])
    listShippers.mockResolvedValue(rows)
    render(<AdminShippersPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Shipper' }))
    return screen.findByRole('table', { name: 'Danh sách shipper' })
  }

  it('separates pending activation, active and locked accounts', async () => {
    const table = within(
      await openShippers([
        shipper(),
        shipper({ id: 's2', account_status: 'PENDING_ACTIVATION' }),
        shipper({ id: 's3', active: false, account_status: 'LOCKED' }),
      ]),
    )
    expect(table.getByText('Hoạt động')).toBeTruthy()
    expect(table.getByText('Chờ kích hoạt')).toBeTruthy()
    expect(table.getByText('Đã khoá')).toBeTruthy()
    expect(table.getAllByText('khu Hải Châu, Đà Nẵng')).toHaveLength(3)
  })

  it('confirms locking and shows the unfinished trips', async () => {
    setShipperLocked.mockResolvedValue(undefined)
    await openShippers([shipper()])
    fireEvent.click(screen.getByRole('button', { name: 'Khoá' }))
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByText(/đang có 2 chuyến chưa xong/)).toBeTruthy()
    expect(setShipperLocked).not.toHaveBeenCalled()
    fireEvent.click(dialog.getByRole('button', { name: 'Khoá shipper' }))
    await waitFor(() => expect(setShipperLocked).toHaveBeenCalledWith('s1', true))
  })

  it('creates a shipper from listed areas and reuses the idempotency key on retry', async () => {
    createShipper
      .mockRejectedValueOnce({
        response: { status: 503, data: { error: { message: 'Lỗi mạng' } } },
      })
      .mockResolvedValueOnce({ id: 's9', name: 'Lê Mới' })
    await openShippers([])
    fireEvent.click(screen.getByRole('button', { name: 'Thêm shipper' }))
    const dialog = within(screen.getByRole('dialog'))
    const fill = (label, value) =>
      fireEvent.change(dialog.getByLabelText(label), { target: { value } })
    fill(/Họ tên/, 'Lê Mới')
    fill(/Số điện thoại/, '0905111222')
    fill(/^Email/, 'moi@vmarket.local')
    fill(/Tên đăng nhập/, 'le.moi')
    fill(/Mật khẩu tạm/, 'matkhau123')
    const submit = dialog.getByRole('button', { name: 'Tạo shipper' })
    expect(submit.disabled).toBe(true)
    fill(/Khu phụ trách/, 'Sơn Trà')
    fireEvent.click(submit)
    expect(await dialog.findByText('Lỗi mạng')).toBeTruthy()
    fireEvent.click(submit)
    expect(await screen.findByText(/Đã tạo shipper Lê Mới/)).toBeTruthy()
    expect(createShipper.mock.calls[0][1]).toBe(createShipper.mock.calls[1][1])
    expect(createShipper.mock.calls[0][0]).toEqual({
      account: { email: 'moi@vmarket.local', username: 'le.moi', password: 'matkhau123' },
      name: 'Lê Mới',
      phone: '0905111222',
      province: 'Thành phố Đà Nẵng',
      district: 'Sơn Trà',
    })
  })
})
