import { describe, expect, it } from 'vitest'
import { formatMegabytes, isAcceptedImage } from './imageFile'

describe('image file helpers', () => {
  it.each([
    [12_000_000, '12 MB'],
    [2_400_000, '2,4 MB'],
    [10_000_000, '10 MB'],
    [150_000, '0,2 MB'],
    [1_843, '2 KB'],
    [120, '1 KB'],
  ])('formats %i bytes as %s', (bytes, label) => {
    expect(formatMegabytes(bytes)).toBe(label)
  })

  it('accepts JPEG, PNG and WebP, guessing from the extension when the type is empty', () => {
    expect(isAcceptedImage(new File([''], 'a.jpg', { type: 'image/jpeg' }))).toBe(true)
    expect(isAcceptedImage(new File([''], 'a.webp', { type: '' }))).toBe(true)
    expect(isAcceptedImage(new File([''], 'a.gif', { type: 'image/gif' }))).toBe(false)
    expect(isAcceptedImage(null)).toBe(false)
  })
})
