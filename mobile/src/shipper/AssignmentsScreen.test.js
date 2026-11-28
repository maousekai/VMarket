import { fireEvent, render, screen, waitFor } from '@testing-library/react-native'
import * as Location from 'expo-location'
import * as SecureStore from 'expo-secure-store'
import * as TaskManager from 'expo-task-manager'
import AssignmentsScreen from './AssignmentsScreen'
import { resetThrottleForTests } from './locationTracking'
import {
  listMyAssignments,
  retryAssignment,
  sendLocation,
  updateAssignmentStatus,
} from '../api/deliveryApi'

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

beforeEach(() => {
  jest.clearAllMocks()
  SecureStore.__store.clear()
  resetThrottleForTests()
  sendLocation.mockResolvedValue({})
})

/** watchPositionAsync gia: phat ngay mot diem GPS. */
const emitOnePosition = () =>
  Location.watchPositionAsync.mockImplementationOnce(async (options, callback) => {
    callback({ coords: { latitude: 16.05, longitude: 108.2 } })
    return { remove: jest.fn() }
  })

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

it('starts GPS only for moving trips and says "sharing" only after a point was accepted', async () => {
  emitOnePosition()
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Đang chia sẻ vị trí cho 1 chuyến/)).toBeTruthy()
  expect(sendLocation).toHaveBeenCalledWith('a1', { latitude: 16.05, longitude: 108.2 })
})

it('waits for a GPS fix instead of claiming to share', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Đang chờ tín hiệu GPS/)).toBeTruthy()
})

it('warns when device location services are off', async () => {
  Location.hasServicesEnabledAsync.mockResolvedValueOnce(false)
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Định vị của máy đang tắt/)).toBeTruthy()
  expect(screen.getByText('Bật định vị')).toBeTruthy()
})

it('stops watching and says so when the server revokes access (403)', async () => {
  const remove = jest.fn()
  Location.watchPositionAsync.mockImplementationOnce(async (options, callback) => {
    setTimeout(() => callback({ coords: { latitude: 16.05, longitude: 108.2 } }), 0)
    return { remove }
  })
  sendLocation.mockRejectedValue(
    Object.assign(new Error('403'), {
      response: {
        status: 403,
        data: { error: { code: 'SHIPPER_INACTIVE', message: 'Shipper đã bị khoá' } },
      },
    }),
  )
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Đã dừng chia sẻ vị trí: Shipper đã bị khoá/)).toBeTruthy()
  expect(remove).toHaveBeenCalled()
})

it('requires location permission before confirming pickup', async () => {
  Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ status: 'denied' })
  listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Đã lấy hàng'))
  expect(await screen.findByText(/Cần cấp quyền vị trí trước khi xác nhận lấy hàng/)).toBeTruthy()
  expect(updateAssignmentStatus).not.toHaveBeenCalled()
})

it('blocks pickup if location services are disabled even when permission is granted', async () => {
  Location.hasServicesEnabledAsync.mockResolvedValueOnce(false)
  listMyAssignments.mockResolvedValue([trip()])
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Đã lấy hàng'))

  expect(await screen.findByText('Cần bật định vị trước khi xác nhận lấy hàng.')).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Bật định vị' })).toBeTruthy()
  expect(updateAssignmentStatus).not.toHaveBeenCalled()
})

it('includes older moving assignments and expands the finished list on demand', async () => {
  const finished = Array.from({ length: 50 }, (_, index) =>
    trip({ id: `finished-${index}`, orderId: String(index + 10000000), status: 'DELIVERED' }),
  )
  listMyAssignments
    .mockResolvedValueOnce(finished)
    .mockResolvedValueOnce([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)

  expect(await screen.findByText('Giao thành công')).toBeTruthy()
  expect(listMyAssignments).toHaveBeenNthCalledWith(2, { page: 1, size: 50 })
  await fireEvent.press(screen.getByRole('tab', { name: 'Đã kết thúc' }))
  expect(await screen.findByText('Tải thêm')).toBeTruthy()
  await fireEvent.press(screen.getByText('Tải thêm'))
  expect(screen.getByText('Tải thêm')).toBeTruthy()
})

it('hides the background sharing action when background tasks are unavailable', async () => {
  TaskManager.isAvailableAsync.mockResolvedValue(false)
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  try {
    await render(<AssignmentsScreen />)
    expect(await screen.findByText(/Đang chờ tín hiệu GPS/)).toBeTruthy()
    expect(screen.queryByText('Bật chia sẻ khi chạy nền')).toBeNull()
  } finally {
    TaskManager.isAvailableAsync.mockResolvedValue(true)
  }
})

it('hides the buyer address and COD while returning the parcel', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'RETURNING' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Hoàn hàng về shop/)).toBeTruthy()
  expect(screen.queryByText(/COD/)).toBeNull()
  expect(screen.queryByText('1 Bạch Đằng, Đà Nẵng')).toBeNull()
})

it('uses the shared label for an assigned trip', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText('Đã phân công shipper')).toBeTruthy()
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
