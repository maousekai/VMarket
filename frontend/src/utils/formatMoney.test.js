import { describe, expect, it } from 'vitest'
import { formatMoney } from './formatMoney'

const normalize = (text) => text.replace(/\s/g, ' ')

describe('formatMoney', () => {
  it('formats whole VND with Vietnamese grouping', () => {
    expect(normalize(formatMoney(459000, 'VND'))).toBe('459.000 ₫')
  })

  it('defaults to VND when no currency is given', () => {
    expect(normalize(formatMoney(2890000))).toBe('2.890.000 ₫')
  })

  it('treats other currencies as minor units', () => {
    expect(formatMoney(1999, 'USD')).toContain('19,99')
  })

  it('returns an empty string for missing amounts', () => {
    expect(formatMoney(null)).toBe('')
    expect(formatMoney(undefined)).toBe('')
    expect(formatMoney('abc')).toBe('')
  })
})
