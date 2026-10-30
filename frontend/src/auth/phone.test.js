import { describe, expect, it } from 'vitest'
import { normalizePhone } from './phone'

describe('normalizePhone', () => {
  it('removes spaces, dots and dashes from Vietnamese phone numbers', () => {
    expect(normalizePhone('0905 123 456')).toBe('0905123456')
    expect(normalizePhone('0905.123.456')).toBe('0905123456')
    expect(normalizePhone('0905-123-456')).toBe('0905123456')
    expect(normalizePhone('+84 912 345 678')).toBe('+84912345678')
    expect(normalizePhone('+84.912.345.678')).toBe('+84912345678')
  })

  it('handles empty values', () => {
    expect(normalizePhone('')).toBe('')
    expect(normalizePhone(null)).toBe('')
    expect(normalizePhone(undefined)).toBe('')
  })
})
