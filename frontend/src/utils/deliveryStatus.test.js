import { describe, expect, it } from 'vitest'
import {
  deliveryStatusLabel,
  displayArea,
  isAreaMismatch,
  normalizeArea,
  publicHistory,
  shipperAccountStatus,
} from './deliveryStatus'
import { home, safeReturnTo } from '../auth/navigation'

describe('delivery labels', () => {
  it('uses the agreed staff and buyer wording', () => {
    expect(deliveryStatusLabel('ASSIGNED')).toBe('Đã phân công shipper')
    expect(deliveryStatusLabel('ASSIGNED', 'buyer')).toBe('Đang chuẩn bị hàng')
    expect(deliveryStatusLabel('RETURNING', 'buyer')).toBe(
      'Giao không thành công, đang hoàn về shop',
    )
  })

  it('hides internal reasons and collapses repeated statuses for the buyer', () => {
    expect(
      publicHistory([
        { status: 'ASSIGNED', occurred_at: '1', reason: 'gán khác quận' },
        { status: 'IN_TRANSIT', occurred_at: '2' },
        { status: 'IN_TRANSIT', occurred_at: '3', reason: 'đổi shipper' },
      ]),
    ).toEqual([
      { status: 'ASSIGNED', occurred_at: '1' },
      { status: 'IN_TRANSIT', occurred_at: '2' },
    ])
  })
})

describe('areas', () => {
  it('normalises like delivery-service AreaNames', () => {
    expect(normalizeArea('Thành phố Đà Nẵng')).toBe('da nang')
    expect(normalizeArea('  Quận   Hải Châu ')).toBe('hai chau')
    expect(normalizeArea('TP. Đà Nẵng')).toBe('da nang')
  })

  it('shows the accented name for normalised keys', () => {
    expect(displayArea({ province: 'da nang', district: 'hai chau' })).toBe('Hải Châu, Đà Nẵng')
    expect(displayArea({ province: 'da nang', district: null })).toBe('Đà Nẵng')
    expect(displayArea({})).toBe('—')
  })

  it('matches the backend area rule', () => {
    const order = { province: 'da nang', district: 'hai chau' }
    expect(isAreaMismatch({ province: 'da nang', district: 'hai chau' }, order)).toBe(false)
    expect(isAreaMismatch({ province: 'da nang', district: 'son tra' }, order)).toBe(true)
    expect(isAreaMismatch({ province: 'hue', district: 'hai chau' }, order)).toBe(true)
    // Thieu quan o mot ben: chi so tinh.
    expect(isAreaMismatch({ province: 'da nang', district: null }, order)).toBe(false)
    expect(
      isAreaMismatch({ province: 'da nang', district: 'son tra' }, { province: 'da nang' }),
    ).toBe(false)
  })

  it('derives the account status even from older API responses', () => {
    expect(shipperAccountStatus({ account_status: 'PENDING_ACTIVATION', active: true })).toBe(
      'PENDING_ACTIVATION',
    )
    expect(shipperAccountStatus({ active: false })).toBe('LOCKED')
  })
})

describe('shipper home', () => {
  it('sends shipper-only accounts to the delivery area by default', () => {
    expect(home(['SHIPPER'])).toBe('/shipper')
    expect(safeReturnTo(undefined, ['SHIPPER'])).toBe('/shipper')
    expect(safeReturnTo('/', ['SHIPPER'])).toBe('/shipper')
    expect(safeReturnTo('/profile', ['SHIPPER'])).toBe('/profile')
  })

  it('keeps everyone else on the storefront', () => {
    expect(safeReturnTo(undefined, ['BUYER'])).toBe('/')
    expect(safeReturnTo(undefined, ['ADMIN', 'SHIPPER'])).toBe('/')
  })
})
