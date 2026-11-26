import { describe, expect, it } from 'vitest'
import {
  isTerminalStatus,
  isTrackingStatus,
  parseOrderSnapshot,
  shipperActions,
} from './deliveryApi'

const kinds = (status, attempt = 1) =>
  shipperActions({ status, attempt }).map((action) => action.status || action.kind)

describe('shipperActions', () => {
  it('follows the delivery-service transition rules', () => {
    expect(kinds('ASSIGNED')).toEqual(['PICKED_UP'])
    expect(kinds('PICKED_UP')).toEqual(['IN_TRANSIT'])
    expect(kinds('IN_TRANSIT')).toEqual(['DELIVERED', 'FAILED'])
    expect(kinds('RETURNING')).toEqual(['RETURNED'])
  })

  it('offers a retry only while attempts remain', () => {
    expect(kinds('FAILED', 1)).toEqual(['retry'])
    expect(kinds('FAILED', 2)).toEqual(['retry'])
    expect(kinds('FAILED', 3)).toEqual([])
  })

  it('offers nothing once the trip is over or not yet assigned', () => {
    for (const status of ['WAITING_HANDOFF', 'WAITING_ASSIGNMENT', 'DELIVERED', 'RETURNED'])
      expect(kinds(status)).toEqual([])
  })
})

describe('status helpers', () => {
  it('tracks only moving trips', () => {
    expect(['PICKED_UP', 'IN_TRANSIT', 'RETURNING'].every(isTrackingStatus)).toBe(true)
    expect(['ASSIGNED', 'FAILED', 'DELIVERED'].some(isTrackingStatus)).toBe(false)
  })

  it('treats delivered and returned as terminal', () => {
    expect(isTerminalStatus('DELIVERED')).toBe(true)
    expect(isTerminalStatus('RETURNED')).toBe(true)
    expect(isTerminalStatus('FAILED')).toBe(false)
  })
})

describe('parseOrderSnapshot', () => {
  it('parses the JSON string and tolerates bad input', () => {
    expect(parseOrderSnapshot('{"recipientName":"An"}')).toEqual({ recipientName: 'An' })
    expect(parseOrderSnapshot({ phone: '1' })).toEqual({ phone: '1' })
    expect(parseOrderSnapshot('not json')).toEqual({})
    expect(parseOrderSnapshot(null)).toEqual({})
  })
})
