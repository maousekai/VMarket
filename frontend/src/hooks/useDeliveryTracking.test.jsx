// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import useDeliveryTracking, { resolveSocketUrl } from './useDeliveryTracking'
import { issueTrackingTicket } from '../services/api/deliveryApi'

vi.mock('../services/api/deliveryApi', async (importOriginal) => ({
  ...(await importOriginal()),
  issueTrackingTicket: vi.fn(),
}))

class FakeSocket {
  static instances = []
  constructor(url) {
    this.url = url
    FakeSocket.instances.push(this)
  }
  open() {
    this.onopen?.()
  }
  receive(message) {
    this.onmessage?.({ data: JSON.stringify(message) })
  }
  close() {
    this.closed = true
  }
  drop() {
    this.onclose?.()
  }
}

beforeEach(() => {
  FakeSocket.instances = []
  vi.stubGlobal('WebSocket', FakeSocket)
  issueTrackingTicket.mockResolvedValue({
    ticket: 't',
    webSocketUrl: 'ws://delivery.test/api/delivery/ws?ticket=t',
  })
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

const socket = () => FakeSocket.instances[FakeSocket.instances.length - 1]

describe('useDeliveryTracking', () => {
  it('connects with a one-time ticket and applies updates in sequence order', async () => {
    const { result } = renderHook(() => useDeliveryTracking('order-1'))
    await waitFor(() => expect(socket()).toBeTruthy())
    expect(socket().url).toBe('ws://delivery.test/api/delivery/ws?ticket=t')
    act(() => socket().open())
    expect(result.current.connection).toBe('live')

    const point = (sequence, latitude) => ({
      assignmentId: 'a1',
      sequence,
      status: 'IN_TRANSIT',
      active: true,
      location: { latitude, longitude: 108.2, recordedAt: '2026-11-26T01:00:00Z' },
    })
    act(() => socket().receive(point(5, 16.05)))
    expect(result.current.update.location.latitude).toBe(16.05)
    // Ban tin cu toi muon (sequence nho hon) bi bo qua.
    act(() => socket().receive(point(4, 15.0)))
    expect(result.current.update.location.latitude).toBe(16.05)
    act(() => socket().receive(point(6, 16.06)))
    expect(result.current.update.location.latitude).toBe(16.06)
  })

  it('reconnects with a fresh ticket after the socket drops', async () => {
    renderHook(() => useDeliveryTracking('order-1'))
    await waitFor(() => expect(FakeSocket.instances).toHaveLength(1))
    vi.useFakeTimers()
    act(() => socket().drop())
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500)
    })
    expect(issueTrackingTicket).toHaveBeenCalledTimes(2)
    expect(FakeSocket.instances).toHaveLength(2)
  })

  it('stops for good once the trip is delivered', async () => {
    const { result } = renderHook(() => useDeliveryTracking('order-1'))
    await waitFor(() => expect(socket()).toBeTruthy())
    act(() => socket().open())
    act(() => socket().receive({ sequence: 9, status: 'DELIVERED', active: false, location: {} }))
    vi.useFakeTimers()
    act(() => socket().drop())
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000)
    })
    expect(result.current.connection).toBe('ended')
    expect(issueTrackingTicket).toHaveBeenCalledTimes(1)
  })

  it('gives up without retrying when tracking is forbidden', async () => {
    issueTrackingTicket.mockRejectedValue({
      response: { status: 403, data: { error: { code: 'FORBIDDEN', message: 'Không có quyền' } } },
    })
    const { result } = renderHook(() => useDeliveryTracking('order-1'))
    await waitFor(() => expect(result.current.connection).toBe('error'))
    expect(result.current.error).toBe('Không có quyền')
    expect(FakeSocket.instances).toHaveLength(0)
  })

  it('does nothing while disabled', () => {
    renderHook(() => useDeliveryTracking('order-1', { enabled: false }))
    expect(issueTrackingTicket).not.toHaveBeenCalled()
  })
})

describe('resolveSocketUrl', () => {
  it('keeps absolute URLs and resolves relative ones against the page origin', () => {
    expect(resolveSocketUrl({ webSocketUrl: 'wss://x/ws?ticket=1' })).toBe('wss://x/ws?ticket=1')
    expect(resolveSocketUrl({ ticket: 'abc' })).toBe(
      `ws://${window.location.host}/api/delivery/ws?ticket=abc`,
    )
  })
})
