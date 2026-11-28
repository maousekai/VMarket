import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native'
import { Alert } from 'react-native'
import * as Location from 'expo-location'
import * as SecureStore from 'expo-secure-store'
import * as TaskManager from 'expo-task-manager'
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake'
import { router } from 'expo-router'
import { resetCatalogLookup } from '../buyer/catalog'
import AssignmentDetailScreen from './AssignmentDetailScreen'
import AssignmentsListScreen from './AssignmentsListScreen'
import HistoryScreen from './HistoryScreen'
import { resetThrottleForTests } from './locationTracking'
import { GPS_STALL_MS, ShipperProvider } from './ShipperProvider'
import {
  confirmPickup,
  confirmReturn,
  endShift,
  getShift,
  listMyAssignments,
  retryAssignment,
  sendLocations,
  startShift,
  updateAssignmentStatus,
} from '../api/deliveryApi'
import { getProduct } from '../api/shopApi'

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
jest.mock('../api/shopApi', () => ({ getProduct: jest.fn(), getShop: jest.fn() }))

const trip = (overrides = {}) => ({
  id: 'a1',
  orderId: '01JORDER0000000000000ABCD1',
  status: 'ASSIGNED',
  attempt: 1,
  version: 3,
  paymentMethod: 'COD',
  codAmount: 150000,
  recipient: { name: 'Nguyễn Văn An', phone: '0905123456', address: '1 Bạch Đằng, Đà Nẵng' },
  pickups: [
    {
      shop_id: 'shop-1',
      name: 'Gian hàng A',
      items: [{ productId: 'p1', variantId: 'v1', quantity: 2 }],
    },
  ],
  ...overrides,
})

const renderList = () =>
  render(
    <ShipperProvider>
      <AssignmentsListScreen />
    </ShipperProvider>,
  )
const renderDetail = (id = 'a1') =>
  render(
    <ShipperProvider>
      <AssignmentDetailScreen id={id} />
    </ShipperProvider>,
  )

beforeEach(() => {
  jest.clearAllMocks()
  SecureStore.__store.clear()
  resetThrottleForTests()
  resetCatalogLookup()
  listMyAssignments.mockReset()
  sendLocations.mockResolvedValue({ results: [{ assignmentId: 'a1', result: 'OK' }] })
  getShift.mockResolvedValue({ onShift: true, unpickedTrips: 0, activeTrips: 0 })
  getProduct.mockResolvedValue({
    product: {
      id: 'p1',
      name: 'Ly Gốm Men Mát',
      variants: [{ id: 'v1', attributes: { color: 'Trắng Ngà' } }],
    },
  })
})

/** watchPositionAsync gia: phat ngay mot diem GPS. */
const emitOnePosition = () =>
  Location.watchPositionAsync.mockImplementationOnce(async (options, callback) => {
    callback({ coords: { latitude: 16.05, longitude: 108.2 } })
    return { remove: jest.fn() }
  })

describe('assigned trips list', () => {
  it('lists the trip with pickup shop, recipient and COD, and opens the detail', async () => {
    listMyAssignments.mockResolvedValue([trip()])
    await renderList()
    expect(await screen.findByText('Giao cho Nguyễn Văn An')).toBeTruthy()
    expect(screen.getByText('Lấy hàng · Gian hàng A')).toBeTruthy()
    expect(screen.getByText('1 Bạch Đằng, Đà Nẵng')).toBeTruthy()
    expect(screen.getByText(/COD 150\.000đ/)).toBeTruthy()
    expect(listMyAssignments).toHaveBeenCalledWith({ scope: 'active' })
    await fireEvent.press(screen.getByText('Xem chi tiết đơn'))
    expect(router.push).toHaveBeenCalledWith({
      pathname: '/assignment/[id]',
      params: { id: 'a1' },
    })
  })

  it('uses the shared label for an assigned trip', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
    await renderList()
    expect(await screen.findByText('Đã phân công shipper')).toBeTruthy()
  })

  it('hides the buyer address and COD while returning the parcel', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'RETURNING' })])
    await renderList()
    expect(await screen.findByText(/Hoàn hàng/)).toBeTruthy()
    expect(screen.queryByText(/COD/)).toBeNull()
    expect(screen.queryByText('1 Bạch Đằng, Đà Nẵng')).toBeNull()
  })
})

describe('trip detail', () => {
  it('shows the products and quantity to pick up at each shop', async () => {
    listMyAssignments.mockResolvedValue([trip()])
    await renderDetail()
    expect(await screen.findByText('Chi tiết đơn được phân công')).toBeTruthy()
    expect(await screen.findByText('Ly Gốm Men Mát / Trắng Ngà')).toBeTruthy()
    expect(screen.getByText('Số lượng: 2')).toBeTruthy()
    expect(getProduct).toHaveBeenCalledWith('p1')
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
    await renderDetail()
    await fireEvent.press((await screen.findAllByText('Đã lấy hàng ở shop này'))[0])
    await waitFor(() => expect(screen.getAllByText('Đã lấy hàng ở shop này')).toHaveLength(1))
    expect(screen.getByText('Lấy hàng')).toBeTruthy()
    expect(screen.queryByText('Bắt đầu giao')).toBeNull()
    await fireEvent.press(screen.getByText('Đã lấy hàng ở shop này'))
    expect(await screen.findByText('Bắt đầu giao')).toBeTruthy()
    expect(confirmPickup.mock.calls).toEqual([
      ['a1', 'shop-1', 3],
      ['a1', 'shop-2', 4],
    ])
  })

  it('confirms COD with the exact amount in one tap', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'DELIVERED', version: 4 }))
    await renderDetail()
    await fireEvent.press(await screen.findByText('Giao hàng và xác nhận COD'))
    expect(screen.getByText('Xác nhận COD')).toBeTruthy()
    expect(screen.getByText(/thu đủ 150\.000đ tiền mặt/)).toBeTruthy()
    await fireEvent.press(screen.getByText('Đã thu đủ 150.000đ'))
    await waitFor(() =>
      expect(updateAssignmentStatus).toHaveBeenCalledWith('a1', {
        status: 'DELIVERED',
        expectedVersion: 3,
        codCollected: 150000,
      }),
    )
    expect((await screen.findAllByText('Giao thành công')).length).toBeGreaterThan(0)
    expect(screen.getByText('Đã giao xong')).toBeTruthy()
  })

  it('closes the COD sheet without saving when the cash is short', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    await renderDetail()
    await fireEvent.press(await screen.findByText('Giao hàng và xác nhận COD'))
    await fireEvent.press(screen.getByText('Chưa thu đủ tiền'))
    expect(updateAssignmentStatus).not.toHaveBeenCalled()
    expect(screen.queryByText('Xác nhận COD')).toBeNull()
  })

  it('shows why the COD could not be saved', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    updateAssignmentStatus.mockRejectedValue({
      response: { status: 409, data: { error: { code: 'X', message: 'Mất kết nối' } } },
    })
    await renderDetail()
    await fireEvent.press(await screen.findByText('Giao hàng và xác nhận COD'))
    await fireEvent.press(screen.getByText('Đã thu đủ 150.000đ'))
    expect(await screen.findByText('Chưa lưu được: Mất kết nối')).toBeTruthy()
  })

  it('requires a note for Other and submits the standard reason code', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    updateAssignmentStatus.mockResolvedValue(trip({ status: 'FAILED', version: 4 }))
    await renderDetail()
    await fireEvent.press(await screen.findByText('Không giao được'))
    await fireEvent.press(screen.getByRole('radio', { name: 'Khác' }))
    await fireEvent.press(screen.getByText('Lưu lý do giao thất bại'))
    expect(updateAssignmentStatus).not.toHaveBeenCalled()
    await fireEvent.changeText(screen.getByLabelText('Ghi chú (bắt buộc)'), 'Ghi chú nội bộ')
    await fireEvent.press(screen.getByText('Lưu lý do giao thất bại'))
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
    await renderDetail()
    await fireEvent.press(await screen.findByText('Không giao được'))
    await fireEvent.press(screen.getByRole('radio', { name: 'Khách từ chối nhận hàng' }))
    expect(screen.getByText(/sẽ chuyển ngay sang hoàn hàng/)).toBeTruthy()
    await fireEvent.press(screen.getByText('Lưu lý do giao thất bại'))
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
    await renderDetail()
    expect(await screen.findByText(/Có thể giao lại sau/)).toBeTruthy()
    expect(screen.getByText(/Khách hẹn giao lại/)).toBeTruthy()
    await fireEvent.press(screen.getByText('Giao lại'))
    expect(retryAssignment).not.toHaveBeenCalled()
  })

  it('retries a failed trip', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'FAILED' })])
    retryAssignment.mockResolvedValue(trip({ status: 'IN_TRANSIT', attempt: 2, version: 4 }))
    await renderDetail()
    await fireEvent.press(await screen.findByText('Giao lại'))
    await waitFor(() => expect(retryAssignment).toHaveBeenCalledWith('a1', 3))
  })

  it('requires location permission before confirming pickup', async () => {
    Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ status: 'denied' })
    listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
    await renderDetail()
    await fireEvent.press(await screen.findByText('Đã lấy hàng ở shop này'))
    expect(await screen.findByText(/Cần cấp quyền vị trí trước khi xác nhận lấy hàng/)).toBeTruthy()
    expect(confirmPickup).not.toHaveBeenCalled()
  })

  it('blocks pickup if location services are disabled even when permission is granted', async () => {
    Location.hasServicesEnabledAsync.mockResolvedValueOnce(false)
    listMyAssignments.mockResolvedValue([trip()])
    await renderDetail()
    await fireEvent.press(await screen.findByText('Đã lấy hàng ở shop này'))
    expect(await screen.findByText('Cần bật định vị trước khi xác nhận lấy hàng.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Bật định vị' })).toBeTruthy()
    expect(confirmPickup).not.toHaveBeenCalled()
  })

  it('does not ask for location before pickup', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
    await renderDetail()
    await screen.findByText('Đã lấy hàng ở shop này')
    expect(Location.requestForegroundPermissionsAsync).not.toHaveBeenCalled()
  })

  it('confirms a return for the selected shop and stops tracking at drop-off', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
      buttons[1].onPress()
    })
    try {
      listMyAssignments.mockResolvedValue([trip({ status: 'RETURNING' })])
      confirmReturn.mockResolvedValue(trip({ status: 'RETURN_DROPPED_OFF', version: 4 }))
      await renderDetail()
      expect(await screen.findByText('Hoàn về gian hàng')).toBeTruthy()
      expect(screen.queryByText(/COD/)).toBeNull()
      await fireEvent.press(screen.getByText('Đã trả hàng ở shop này'))
      expect(confirmReturn).toHaveBeenCalledWith('a1', 'shop-1', 3)
      expect(await screen.findByText('Đã bàn giao hoàn về')).toBeTruthy()
      await waitFor(() =>
        expect(SecureStore.__store.has('vmarket.trackingAssignments')).toBe(false),
      )
      expect(screen.queryByText('Đã trả hàng ở shop này')).toBeNull()
    } finally {
      alert.mockRestore()
    }
  })

  it('opens a finished trip from history', async () => {
    listMyAssignments.mockImplementation(async ({ scope }) =>
      scope === 'finished'
        ? [trip({ id: 'f1', status: 'DELIVERED', completedAt: '2026-11-27T11:00:00Z' })]
        : [],
    )
    await renderDetail('f1')
    expect(await screen.findByText('Đã giao xong', {}, { timeout: 3000 })).toBeTruthy()
    expect(screen.queryByText('Đã lấy hàng ở shop này')).toBeNull()
  })
})

describe('GPS sharing', () => {
  it('starts GPS only for moving trips and says "sharing" only after a point was accepted', async () => {
    emitOnePosition()
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    await renderList()
    expect(await screen.findByText(/Đang chia sẻ vị trí cho 1 chuyến/)).toBeTruthy()
    expect(sendLocations).toHaveBeenCalledWith({ latitude: 16.05, longitude: 108.2 })
  })

  it('keeps the screen awake while a trip needs GPS and releases it afterwards', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    const view = await renderList()
    await waitFor(() => expect(activateKeepAwakeAsync).toHaveBeenCalledWith('vmarket-shipper-trip'))
    await view.unmount()
    expect(deactivateKeepAwake).toHaveBeenCalledWith('vmarket-shipper-trip')
  })

  it('does not keep the screen awake before pickup', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'ASSIGNED' })])
    await renderList()
    await screen.findByText('Xem chi tiết đơn')
    expect(activateKeepAwakeAsync).not.toHaveBeenCalled()
  })

  it('warns with ROM guidance when no point reaches the server for too long', async () => {
    jest.useFakeTimers({ doNotFake: ['nextTick', 'setImmediate'] })
    try {
      listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
      await renderList()
      expect(await screen.findByText(/Đang chờ tín hiệu GPS/)).toBeTruthy()
      await act(async () => {
        jest.advanceTimersByTime(GPS_STALL_MS + 20_000)
      })
      expect(await screen.findByText(/Mất kết nối GPS/)).toBeTruthy()
      expect(screen.getByText(/Cho phép chạy nền \/ Tự khởi động/)).toBeTruthy()
      expect(screen.getByText('Mở cài đặt ứng dụng')).toBeTruthy()
    } finally {
      jest.useRealTimers()
    }
  })

  it('waits for a GPS fix instead of claiming to share', async () => {
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    await renderList()
    expect(await screen.findByText(/Đang chờ tín hiệu GPS/)).toBeTruthy()
  })

  it('warns when device location services are off', async () => {
    Location.hasServicesEnabledAsync.mockResolvedValueOnce(false)
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    await renderList()
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
    await renderList()
    expect(await screen.findByText(/Đã dừng chia sẻ vị trí: Shipper đã bị khoá/)).toBeTruthy()
    expect(remove).toHaveBeenCalled()
  })

  it('hides the background sharing action when background tasks are unavailable', async () => {
    TaskManager.isAvailableAsync.mockResolvedValue(false)
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    try {
      await renderList()
      expect(await screen.findByText(/Đang chờ tín hiệu GPS/)).toBeTruthy()
      expect(screen.queryByText('Bật chia sẻ khi chạy nền')).toBeNull()
    } finally {
      TaskManager.isAvailableAsync.mockResolvedValue(true)
    }
  })

  it('explains how to fix a denied location permission', async () => {
    Location.requestForegroundPermissionsAsync.mockResolvedValueOnce({ status: 'denied' })
    listMyAssignments.mockResolvedValue([trip({ status: 'IN_TRANSIT' })])
    await renderList()
    expect(await screen.findByText(/Chưa có quyền vị trí/)).toBeTruthy()
    expect(screen.getByText('Mở cài đặt')).toBeTruthy()
  })
})

describe('history', () => {
  it('pages finished trips and shows each journey', async () => {
    const finished = Array.from({ length: 20 }, (_, index) =>
      trip({
        id: `finished-${index}`,
        orderId: String(index + 10000000),
        status: 'DELIVERED',
        assignedAt: '2026-11-27T06:40:00Z',
        completedAt: '2026-11-27T07:32:00Z',
      }),
    )
    listMyAssignments
      .mockResolvedValueOnce(finished)
      .mockResolvedValueOnce([trip({ id: 'last', orderId: 'last', status: 'RETURN_DROPPED_OFF' })])
    await render(<HistoryScreen />)
    expect((await screen.findAllByText(/Nhận phân công/)).length).toBe(20)
    expect(screen.getAllByText('Đã thu COD: 150.000đ').length).toBe(20)
    await fireEvent.press(screen.getByText('Tải thêm'))
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
})

describe('shift', () => {
  it('warns while off shift and starts a shift without turning GPS on', async () => {
    listMyAssignments.mockResolvedValue([])
    // Thanh ca doc lai trang thai tu server sau moi lan lam moi danh sach.
    getShift.mockResolvedValue({ onShift: false, unpickedTrips: 0, activeTrips: 0 })
    startShift.mockResolvedValue({ onShift: true, unpickedTrips: 0, activeTrips: 0 })
    await renderList()
    expect(await screen.findByText('Bạn đang ngoài ca, sẽ không được giao đơn mới.')).toBeTruthy()
    getShift.mockResolvedValue({ onShift: true, unpickedTrips: 0, activeTrips: 0 })
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
    await renderList()
    await fireEvent.press(await screen.findByText('Kết thúc ca'))
    await waitFor(() => expect(alert).toHaveBeenCalled())
    const [title, , buttons] = alert.mock.calls[0]
    expect(title).toBe('Kết thúc ca?')
    getShift.mockResolvedValue({ onShift: false, unpickedTrips: 0, activeTrips: 0 })
    await act(async () => {
      await buttons.find((button) => button.text === 'Kết thúc ca').onPress()
    })
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
    await renderList()
    await fireEvent.press(await screen.findByText('Kết thúc ca'))
    expect(await screen.findByText(/Còn 1 chuyến đã lấy hàng/)).toBeTruthy()
  })
})
