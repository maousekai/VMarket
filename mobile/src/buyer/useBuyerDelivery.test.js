import { act, renderHook, waitFor } from '@testing-library/react-native'
import { getDelivery, issueTrackingTicket } from '../api/deliveryApi'
import useBuyerDelivery, { resolveTrackingUrl } from './useBuyerDelivery'

jest.mock('expo-router', () => ({
  useFocusEffect: (callback) => require('react').useEffect(callback, [callback]),
}))
jest.mock('../api/deliveryApi', () => ({ getDelivery: jest.fn(), issueTrackingTicket: jest.fn() }))

const sockets = []
const OriginalWebSocket = global.WebSocket
const assignment = {
  id: 'assignment-1',
  status: 'IN_TRANSIT',
  location: { latitude: 16.1, longitude: 108.2 },
}

beforeEach(() => {
  sockets.length = 0
  jest.clearAllMocks()
  global.WebSocket = jest.fn(function mockSocket() {
    this.close = jest.fn()
    sockets.push(this)
  })
  getDelivery.mockResolvedValue({ assignment })
  issueTrackingTicket.mockResolvedValue({
    webSocketUrl: 'wss://shop.example.test/api/delivery/ws?ticket=server',
  })
})

afterEach(() => {
  global.WebSocket = OriginalWebSocket
})

it('resolves relative tickets against the reverse proxy and rejects insecure release URLs', () => {
  expect(
    resolveTrackingUrl(
      { webSocketUrl: '/api/delivery/ws?ticket=one' },
      'https://shop.example.test',
    ),
  ).toBe('wss://shop.example.test/api/delivery/ws?ticket=one')
  expect(() =>
    resolveTrackingUrl(
      { webSocketUrl: 'ws://shop.example.test/ws?ticket=one' },
      'https://shop.example.test',
    ),
  ).toThrow()
})

it('ignores out-of-order positions and removes the location when tracking ends', async () => {
  const view = await renderHook(() => useBuyerDelivery('order-1'))
  await waitFor(() => expect(sockets).toHaveLength(1))
  const [socket] = sockets
  const emit = (status, sequence, location) =>
    socket.onmessage({
      data: JSON.stringify({
        assignmentId: assignment.id,
        status,
        sequence,
        location,
      }),
    })
  await act(() => {
    socket.onopen()
    emit('IN_TRANSIT', 2, { latitude: 16.2, longitude: 108.3 })
    emit('IN_TRANSIT', 1, assignment.location)
  })
  expect(view.result.current.assignment.location.latitude).toBe(16.2)
  await act(() => emit('RETURNING', 3, assignment.location))
  expect(view.result.current.authorized).toBe(false)
  expect(view.result.current.assignment.location).toBeNull()
  expect(socket.close).toHaveBeenCalledTimes(1)
})

it('does not request a tracking ticket after delivery', async () => {
  getDelivery.mockResolvedValue({ assignment: { ...assignment, status: 'DELIVERED' }, history: [] })
  const view = await renderHook(() => useBuyerDelivery('order-1'))
  await waitFor(() => expect(view.result.current.loading).toBe(false))
  expect(view.result.current.authorized).toBe(false)
  expect(issueTrackingTicket).not.toHaveBeenCalled()
})
