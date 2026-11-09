import { describe, expect, it } from 'vitest'
import {
  attributeGroups,
  describeVariant,
  findVariant,
  initialSelection,
  isOptionAvailable,
} from './variant'

const variants = [
  { id: 'v1', attributes: { Màu: 'Đen', Size: 'M' }, price: 100, availableStock: 0 },
  { id: 'v2', attributes: { Màu: 'Đen', Size: 'L' }, price: 110, availableStock: 3 },
  { id: 'v3', attributes: { Màu: 'Trắng', Size: 'M' }, price: 100, availableStock: 5 },
]

describe('variant helpers', () => {
  it('groups attribute values in first-seen order', () => {
    expect(attributeGroups(variants)).toEqual([
      { name: 'Màu', values: ['Đen', 'Trắng'] },
      { name: 'Size', values: ['M', 'L'] },
    ])
  })

  it('needs every attribute before resolving a variant', () => {
    expect(findVariant(variants, { Màu: 'Đen' })).toBeNull()
    expect(findVariant(variants, { Màu: 'Đen', Size: 'L' }).id).toBe('v2')
    expect(findVariant(variants, { Màu: 'Trắng', Size: 'L' })).toBeNull()
  })

  it('resolves the only variant without a selection', () => {
    expect(findVariant([variants[2]], {}).id).toBe('v3')
  })

  it('marks options that only lead to sold-out variants as unavailable', () => {
    expect(isOptionAvailable(variants, { Màu: 'Đen' }, 'Size', 'M')).toBe(false)
    expect(isOptionAvailable(variants, { Màu: 'Đen' }, 'Size', 'L')).toBe(true)
    expect(isOptionAvailable(variants, { Size: 'L' }, 'Màu', 'Trắng')).toBe(false)
  })

  it('starts from the first variant in stock', () => {
    expect(initialSelection(variants)).toEqual({ Màu: 'Đen', Size: 'L' })
  })

  it('describes a variant', () => {
    expect(describeVariant(variants[0])).toBe('Màu: Đen · Size: M')
    expect(describeVariant({ attributes: {} })).toBe('')
  })
})
