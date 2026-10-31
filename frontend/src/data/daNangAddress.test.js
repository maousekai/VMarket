import { describe, expect, it } from 'vitest'
import { DA_NANG_PROVINCE, DA_NANG_WARD_GROUPS, DA_NANG_WARDS } from './daNangAddress'

describe('Da Nang address data', () => {
  it('uses the official province name', () => {
    expect(DA_NANG_PROVINCE).toBe('Thành phố Đà Nẵng')
  })

  it('lists 12 phường and 3 xã of the former Da Nang, each once', () => {
    const [phuong, xa] = DA_NANG_WARD_GROUPS
    expect(phuong.label).toBe('Phường')
    expect(phuong.wards).toHaveLength(12)
    expect(phuong.wards.every((ward) => ward.startsWith('Phường '))).toBe(true)
    expect(xa.label).toBe('Xã')
    expect(xa.wards).toEqual(['Xã Bà Nà', 'Xã Hòa Tiến', 'Xã Hòa Vang'])
    expect(DA_NANG_WARDS).toHaveLength(15)
    expect(new Set(DA_NANG_WARDS).size).toBe(15)
  })

  it('keeps every group sorted alphabetically', () => {
    for (const group of DA_NANG_WARD_GROUPS) {
      expect(group.wards).toEqual([...group.wards].sort((a, b) => a.localeCompare(b, 'vi')))
    }
  })

  it('stores names in composed Unicode so they match what the server saves', () => {
    for (const ward of DA_NANG_WARDS) expect(ward).toBe(ward.normalize('NFC'))
  })

  it('leaves out the wards that came from Quảng Nam and the special zone', () => {
    for (const excluded of [
      'Phường Tam Kỳ',
      'Phường Hội An',
      'Phường Điện Bàn',
      'Xã Núi Thành',
      'Xã Đại Lộc',
      'Đặc khu Hoàng Sa',
    ])
      expect(DA_NANG_WARDS).not.toContain(excluded)
  })
})
