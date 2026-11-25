import { describe, expect, it } from 'vitest'
import {
  canCancelOrder,
  canRequestReturn,
  groupItemsByShop,
  orderAddress,
  orderStepIndex,
  returnableItems,
  returnDeadline,
  shortOrderId,
} from './order'

const delivered = {
  status: 'DELIVERED',
  deliveredAt: '2026-11-01T10:00:00Z',
  items: [
    { id: 'i1', shopId: 's1' },
    { id: 'i2', shopId: 's1' },
    { id: 'i3', shopId: 's2' },
  ],
}

describe('order helpers', () => {
  it('maps statuses onto the timeline', () => {
    expect(orderStepIndex('WAITING_PAYMENT')).toBe(0)
    expect(orderStepIndex('PROCESSING')).toBe(1)
    expect(orderStepIndex('COMPLETED')).toBe(4)
    expect(orderStepIndex('CANCELLED')).toBe(-1)
  })

  it('allows cancelling only before the shop starts preparing', () => {
    expect(canCancelOrder({ status: 'PENDING' })).toBe(true)
    expect(canCancelOrder({ status: 'WAITING_PAYMENT' })).toBe(true)
    expect(canCancelOrder({ status: 'PROCESSING' })).toBe(false)
  })

  it('opens returns for 7 days after delivery', () => {
    expect(returnDeadline(delivered).toISOString()).toBe('2026-11-08T10:00:00.000Z')
    expect(canRequestReturn(delivered, [], new Date('2026-11-05T00:00:00Z'))).toBe(true)
    expect(canRequestReturn(delivered, [], new Date('2026-11-09T00:00:00Z'))).toBe(false)
    const completed = { ...delivered, status: 'COMPLETED' }
    expect(canRequestReturn(completed, [], new Date('2026-11-05T00:00:00Z'))).toBe(true)
    expect(canRequestReturn(completed, [], new Date('2026-11-09T00:00:00Z'))).toBe(false)
    expect(canRequestReturn({ ...delivered, status: 'SHIPPED' }, [])).toBe(false)
  })

  it('excludes lines that already have a return request', () => {
    const returns = [{ status: 'PENDING', items: [{ orderItemId: 'i1' }] }]
    expect(returnableItems(delivered, returns).map((item) => item.id)).toEqual(['i2', 'i3'])
    // Chi tra nguyen don: da co mot dong trong yeu cau khac thi khong tao them duoc.
    expect(canRequestReturn(delivered, returns, new Date('2026-11-02T00:00:00Z'))).toBe(false)
    const cancelled = [{ status: 'CANCELLED', items: [{ orderItemId: 'i1' }] }]
    expect(canRequestReturn(delivered, cancelled, new Date('2026-11-02T00:00:00Z'))).toBe(true)
    const all = [
      { status: 'REJECTED', items: ['i1', 'i2', 'i3'].map((orderItemId) => ({ orderItemId })) },
    ]
    expect(canRequestReturn(delivered, all, new Date('2026-11-02T00:00:00Z'))).toBe(false)
  })

  it('groups items by shop and formats ids and addresses', () => {
    expect(groupItemsByShop(delivered.items).map((group) => group.items.length)).toEqual([2, 1])
    expect(shortOrderId('01JC4X9Q2W5R8T6V8N0P4S6A2B')).toBe('#0P4S6A2B')
    expect(orderAddress({ streetAddress: '1 A', ward: 'P1', province: 'Đà Nẵng' })).toBe(
      '1 A, P1, Đà Nẵng',
    )
  })
})
