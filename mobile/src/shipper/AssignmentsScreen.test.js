import { fireEvent, render, screen, waitFor } from '@testing-library/react-native'
import * as Location from 'expo-location'
import AssignmentsScreen from './AssignmentsScreen'
import { listMyAssignments, retryAssignment, updateAssignmentStatus } from '../api/deliveryApi'

jest.mock('../api/deliveryApi', () => ({
  ...jest.requireActual('../api/deliveryApi'),
  listMyAssignments: jest.fn(),
  updateAssignmentStatus: jest.fn(),
  retryAssignment: jest.fn(),
  sendLocation: jest.fn(async () => ({})),
}))

const trip = (overrides = {}) => ({
  id: 'a1',
  orderId: '01JORDER0000000000000ABCD1',
  status: 'ASSIGNED',
  attempt: 1,
  version: 3,
  paymentMethod: 'COD',
  codAmount: 150000,
  orderSnapshot: JSON.stringify({
    recipientName: 'Nguyễn Văn An',
    phone: '0905123456',
    streetAddress: '1 Bạch Đằng',
    province: 'Đà Nẵng',
  }),
  ...overrides,
})

beforeEach(() => jest.clearAllMocks())

it('lists the trip with recipient, address and COD', async () => {
  listMyAssignments.mockResolvedValue([trip()])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText('Nguyễn Văn An')).toBeTruthy()
  expect(screen.getByText('1 Bạch Đằng, Đà Nẵng')).toBeTruthy()
  expect(screen.getByText(/COD/)).toBeTruthy()
})

it('advances the trip with the current version', async () => {
  listMyAssignments.mockResolvedValue([trip()])
  updateAssignmentStatus.mockResolvedValue(trip({ status: 'PICKED_UP', version: 4 }))
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Đã lấy hàng'))
  expect(await screen.findByText('Bắt đầu giao')).toBeTruthy()
  expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
    status: 'PICKED_UP',
    expectedVersion: 3,
  })
})

it('confirms delivery only with the exact COD amount', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  updateAssignmentStatus.mockResolvedValue(trip({ status: 'DELIVERED', version: 4 }))
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Giao thành công'))
  await fireEvent.changeText(screen.getByLabelText('Số tiền đã thu (VND)'), '100000')
  await fireEvent.press(screen.getByText('Xác nhận đã giao'))
  expect(updateAssignmentStatus).not.toHaveBeenCalled()
  await fireEvent.changeText(screen.getByLabelText('Số tiền đã thu (VND)'), '150.000')
  await fireEvent.press(screen.getByText('Xác nhận đã giao'))
  await waitFor(() =>
    expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
      status: 'DELIVERED',
      expectedVersion: 3,
      codCollected: 150000,
    }),
  )
})

it('requires a reason to report a failed delivery', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  updateAssignmentStatus.mockResolvedValue(trip({ status: 'FAILED', version: 4 }))
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Giao thất bại'))
  await fireEvent.changeText(screen.getByLabelText('Lý do'), 'Không liên lạc được')
  await fireEvent.press(screen.getByText('Xác nhận thất bại'))
  await waitFor(() =>
    expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
      status: 'FAILED',
      expectedVersion: 3,
      reason: 'Không liên lạc được',
    }),
  )
})

it('retries a failed trip', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'FAILED' })])
  retryAssignment.mockResolvedValue(trip({ status: 'IN_TRANSIT', attempt: 2, version: 4 }))
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Giao lại'))
  await waitFor(() => expect(retryAssignment).toHaveBeenCalledWith('a1', 3))
})

it('starts GPS only for moving trips', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Đang chia sẻ vị trí cho 1 chuyến/)).toBeTruthy()
  expect(Location.watchPositionAsync).toHaveBeenCalled()
})

it('does not ask for location before pickup', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
  await render(<AssignmentsScreen />)
  await screen.findByText('Đã lấy hàng')
  expect(Location.requestForegroundPermissionsAsync).not.toHaveBeenCalled()
})

it('explains how to fix a denied location permission', async () => {
  Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ status: 'denied' })
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Chưa có quyền vị trí/)).toBeTruthy()
  expect(screen.getByText('Mở cài đặt')).toBeTruthy()
})
