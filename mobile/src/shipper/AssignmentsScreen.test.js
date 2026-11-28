import { fireEvent, render, screen, waitFor } from '@testing-library/react-native'
import { Alert } from 'react-native'
import * as Location from 'expo-location'
import * as SecureStore from 'expo-secure-store'
import * as TaskManager from 'expo-task-manager'
import AssignmentsScreen from './AssignmentsScreen'
import { resetThrottleForTests } from './locationTracking'
import {
  confirmPickup,
  confirmReturn,
  listMyAssignments,
  retryAssignment,
  sendLocations,
  updateAssignmentStatus,
  getShift,
  startShift,
  endShift,
} from '../api/deliveryApi'

jest.mock('../api/deliveryApi', () => ({
  ...jest.requireActual('../api/deliveryApi'),
  confirmPickup: jest.fn(),
  confirmReturn: jest.fn(),
  listMyAssignments: jest.fn(),
  updateAssignmentStatus: jest.fn(),
  retryAssignment: jest.fn(),
  sendLocations: jest.fn(async () => ({})),
  getShift: jest.fn(async () => ({ onShift: true, unpickedTrips: 0, activeTrips: 0 })),
  startShift: jest.fn(),
  endShift: jest.fn(),
}))

const trip = (overrides = {}) => ({
  id: 'a1',
  orderId: '01JORDER0000000000000ABCD1',
  status: 'ASSIGNED',
  attempt: 1,
  version: 3,
  paymentMethod: 'COD',
  codAmount: 150000,
  recipient: { name: 'Nguyễn Văn An', phone: '0905123456', address: '1 Bạch Đằng, Đà Nẵng' },
  pickups: [{ shop_id: 'shop-1', name: 'Gian hàng A' }],
  ...overrides,
})

beforeEach(() => {
  jest.clearAllMocks()
  SecureStore.__store.clear()
  resetThrottleForTests()
  listMyAssignments.mockReset()
  sendLocations.mockResolvedValue({ results: [{ assignmentId: 'a1', result: 'OK' }] })
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

it('confirms shop pickups in order with the latest returned version', async () => {
  const shops = [
    { shop_id: 'shop-1', name: 'Gian hàng A' },
    { shop_id: 'shop-2', name: 'Gian hàng B' },
  ]
  listMyAssignments.mockResolvedValue([trip({ pickups: shops })])
  confirmPickup
    .mockResolvedValueOnce(
      trip({
        pickups: [{ ...shops[0], picked_up_at: '2026-11-27T10:00:00Z' }, shops[1]],
        version: 4,
      }),
    )
    .mockResolvedValueOnce(trip({ status: 'PICKED_UP', version: 5 }))
  await render(<AssignmentsScreen />)
  await fireEvent.press((await screen.findAllByText('Đã lấy hàng ở shop này'))[0])
  await waitFor(() => expect(screen.getAllByText('Đã lấy hàng ở shop này')).toHaveLength(1))
  expect(screen.queryByText('Bắt đầu giao')).toBeNull()
  await fireEvent.press(screen.getByText('Đã lấy hàng ở shop này'))
  expect(await screen.findByText('Bắt đầu giao')).toBeTruthy()
  expect(confirmPickup.mock.calls).toEqual([
    ['a1', 'shop-1', 3],
    ['a1', 'shop-2', 4],
  ])
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

it('requires a note for Other and submits the standard reason code', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  updateAssignmentStatus.mockResolvedValue(trip({ status: 'FAILED', version: 4 }))
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Giao thất bại'))
  await fireEvent.press(screen.getByRole('radio', { name: 'Khác' }))
  await fireEvent.press(screen.getByText('Xác nhận thất bại'))
  expect(updateAssignmentStatus).not.toHaveBeenCalled()
  await fireEvent.changeText(screen.getByLabelText('Ghi chú nội bộ (bắt buộc)'), 'Ghi chú nội bộ')
  await fireEvent.press(screen.getByText('Xác nhận thất bại'))
  await waitFor(() =>
    expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
      status: 'FAILED',
      expectedVersion: 3,
      reasonCode: 'OTHER',
      reason: 'Ghi chú nội bộ',
    }),
  )
})

it('warns when the chosen reason sends the parcel directly to return', async () => {
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  updateAssignmentStatus.mockResolvedValue(trip({ status: 'RETURNING', version: 4 }))
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Giao thất bại'))
  await fireEvent.press(screen.getByRole('radio', { name: 'Khách từ chối nhận hàng' }))
  expect(screen.getByText(/sẽ chuyển ngay sang hoàn hàng/)).toBeTruthy()
  await fireEvent.press(screen.getByText('Xác nhận thất bại'))
  await waitFor(() =>
    expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
      status: 'FAILED',
      expectedVersion: 3,
      reasonCode: 'CUSTOMER_REFUSED',
    }),
  )
})

it('keeps retry disabled until the server timestamp and shows public reasons', async () => {
  listMyAssignments.mockResolvedValue([
    trip({
      status: 'FAILED',
      earliestRetryAt: new Date(Date.now() + 3600_000).toISOString(),
      failures: [
        { attempt: 1, public_reason: 'Khách hẹn giao lại', occurred_at: '2026-11-27T10:00:00Z' },
      ],
    }),
  ])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Có thể giao lại sau/)).toBeTruthy()
  expect(screen.getByText(/Khách hẹn giao lại/)).toBeTruthy()
  await fireEvent.press(screen.getByText('Giao lại'))
  expect(retryAssignment).not.toHaveBeenCalled()
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
  expect(sendLocations).toHaveBeenCalledWith({ latitude: 16.05, longitude: 108.2 })
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
  sendLocations.mockRejectedValue(
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
  await fireEvent.press(await screen.findByText('Đã lấy hàng ở shop này'))
  expect(await screen.findByText(/Cần cấp quyền vị trí trước khi xác nhận lấy hàng/)).toBeTruthy()
  expect(updateAssignmentStatus).not.toHaveBeenCalled()
})

it('blocks pickup if location services are disabled even when permission is granted', async () => {
  Location.hasServicesEnabledAsync.mockResolvedValueOnce(false)
  listMyAssignments.mockResolvedValue([trip()])
  await render(<AssignmentsScreen />)
  await fireEvent.press(await screen.findByText('Đã lấy hàng ở shop này'))

  expect(await screen.findByText('Cần bật định vị trước khi xác nhận lấy hàng.')).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Bật định vị' })).toBeTruthy()
  expect(updateAssignmentStatus).not.toHaveBeenCalled()
})

it('loads active trips separately and requests finished pages on demand', async () => {
  const finished = Array.from({ length: 20 }, (_, index) =>
    trip({ id: `finished-${index}`, orderId: String(index + 10000000), status: 'DELIVERED' }),
  )
  listMyAssignments
    .mockResolvedValueOnce([trip({ status: 'IN_TRANSIT' })])
    .mockResolvedValueOnce(finished)
    .mockResolvedValueOnce([trip({ id: 'last', orderId: 'last', status: 'RETURN_DROPPED_OFF' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText('Giao thành công')).toBeTruthy()
  expect(listMyAssignments).toHaveBeenCalledWith({ scope: 'active' })
  await fireEvent.press(screen.getByRole('tab', { name: 'Đã kết thúc' }))
  await fireEvent.press(await screen.findByText('Tải thêm'))
  await waitFor(() =>
    expect(listMyAssignments).toHaveBeenLastCalledWith({
      scope: 'finished',
      page: 1,
      size: 20,
      signal: expect.anything(),
    }),
  )
  await waitFor(() => expect(screen.queryByText('Tải thêm')).toBeNull())
})

it('confirms a return for the selected shop and stops tracking at drop-off', async () => {
  const alert = jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    buttons[1].onPress()
  })
  try {
    listMyAssignments.mockResolvedValue([trip({ status: 'RETURNING' })])
    confirmReturn.mockResolvedValue(trip({ status: 'RETURN_DROPPED_OFF', version: 4 }))
    await render(<AssignmentsScreen />)
    await fireEvent.press(await screen.findByText('Đã trả hàng ở shop này'))
    expect(confirmReturn).toHaveBeenCalledWith('a1', 'shop-1', 3)
    await waitFor(() => expect(SecureStore.__store.has('vmarket.trackingAssignments')).toBe(false))
    expect(screen.queryByText('Đã trả hàng ở shop này')).toBeNull()
  } finally {
    alert.mockRestore()
  }
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
  await screen.findByText('Đã lấy hàng ở shop này')
  expect(Location.requestForegroundPermissionsAsync).not.toHaveBeenCalled()
})

it('explains how to fix a denied location permission', async () => {
  Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ status: 'denied' })
  listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
  await render(<AssignmentsScreen />)
  expect(await screen.findByText(/Chưa có quyền vị trí/)).toBeTruthy()
  expect(screen.getByText('Mở cài đặt')).toBeTruthy()
})

describe('shift', () => {
  it('warns while off shift and starts a shift without turning GPS on', async () => {
    listMyAssignments.mockResolvedValue([])
    getShift.mockResolvedValueOnce({ onShift: false, unpickedTrips: 0, activeTrips: 0 })
    startShift.mockResolvedValue({ onShift: true, unpickedTrips: 0, activeTrips: 0 })
    await render(<AssignmentsScreen />)
    expect(await screen.findByText('Bạn đang ngoài ca, sẽ không được giao đơn mới.')).toBeTruthy()
    await fireEvent.press(screen.getByText('Bắt đầu ca'))
    expect(await screen.findByText('Bạn đang trong ca')).toBeTruthy()
    expect(startShift).toHaveBeenCalled()
    expect(Location.requestForegroundPermissionsAsync).not.toHaveBeenCalled()
  })

  it('asks before ending a shift that still has trips to pick up', async () => {
    listMyAssignments.mockResolvedValue([])
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {})
    endShift
      .mockRejectedValueOnce({
        response: {
          status: 409,
          data: {
            error: {
              code: 'SHIFT_END_CONFIRM_REQUIRED',
              message: 'Còn 1 chuyến chưa lấy hàng sẽ chuyển cho shipper khác',
            },
          },
        },
      })
      .mockResolvedValueOnce({ onShift: false, unpickedTrips: 0, activeTrips: 0 })
    await render(<AssignmentsScreen />)
    await fireEvent.press(await screen.findByText('Kết thúc ca'))
    await waitFor(() => expect(alert).toHaveBeenCalled())
    const [title, , buttons] = alert.mock.calls[0]
    expect(title).toBe('Kết thúc ca?')
    await buttons.find((button) => button.text === 'Kết thúc ca').onPress()
    await waitFor(() => expect(endShift).toHaveBeenLastCalledWith({ releaseUnpicked: true }))
    expect(await screen.findByText('Bạn đang ngoài ca')).toBeTruthy()
    alert.mockRestore()
  })

  it('shows why a shift with picked-up cargo cannot end', async () => {
    listMyAssignments.mockResolvedValue([])
    endShift.mockRejectedValueOnce({
      response: {
        status: 409,
        data: {
          error: {
            code: 'SHIFT_HAS_ACTIVE_TRIPS',
            message: 'Còn 1 chuyến đã lấy hàng; hãy giao xong hoặc trả hàng trước khi kết thúc ca',
          },
        },
      },
    })
    await render(<AssignmentsScreen />)
    await fireEvent.press(await screen.findByText('Kết thúc ca'))
    expect(await screen.findByText(/Còn 1 chuyến đã lấy hàng/)).toBeTruthy()
  })
})
