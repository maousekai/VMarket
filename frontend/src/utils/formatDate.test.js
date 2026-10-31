import { describe, expect, it } from 'vitest'
import { formatDate, formatDateTime } from './formatDate'

describe('date formatting', () => {
  it('formats dates as dd/mm/yyyy in Vietnam time', () => {
    expect(formatDate('2023-01-12T03:00:00Z')).toBe('12/01/2023')
    // 20:00 UTC is already the next day in Vietnam (UTC+7).
    expect(formatDate('2023-01-12T20:00:00Z')).toBe('13/01/2023')
  })

  it('includes the time when asked', () => {
    const text = formatDateTime('2023-01-12T03:05:00Z')
    expect(text).toContain('12/01/2023')
    expect(text).toContain('10:05')
  })

  it('returns an empty string for missing values', () => {
    expect(formatDate(null)).toBe('')
    expect(formatDateTime(undefined)).toBe('')
  })
})
