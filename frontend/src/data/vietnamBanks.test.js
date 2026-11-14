import { describe, expect, it } from 'vitest'
import { bankLabel, findBank, searchBanks, VIETNAM_BANKS } from './vietnamBanks'

const codes = (query) => searchBanks(query).map((bank) => bank.code)

describe('searchBanks', () => {
  it('returns every bank for an empty query', () => {
    expect(searchBanks('  ')).toHaveLength(VIETNAM_BANKS.length)
  })

  it('finds a bank by its short name or code first', () => {
    expect(codes('vcb')[0]).toBe('VCB')
    expect(codes('Vietcombank')).toEqual(['VCB'])
    expect(codes('mb')[0]).toBe('MB')
    expect(codes('MB Bank')[0]).toBe('MB')
  })

  it('finds a bank by its full name without diacritics', () => {
    expect(codes('ngoai thuong')).toContain('VCB')
    expect(codes('Kỹ Thương')).toEqual(['TCB'])
    expect(codes('dau tu va phat trien')).toEqual(['BIDV'])
    expect(codes('Đông Nam Á')).toContain('SEAB')
  })

  it('returns nothing for an unknown bank', () => {
    expect(searchBanks('khong co ngan hang nay')).toEqual([])
  })

  it('labels a stored code', () => {
    expect(findBank('TCB').shortName).toBe('Techcombank')
    expect(bankLabel('VCB')).toBe('Vietcombank – Ngân hàng TMCP Ngoại thương Việt Nam')
    expect(bankLabel('XYZ')).toBe('XYZ')
  })
})
