import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { bindSessionManager } from './apiClient'
import {
  addressRequestBody,
  changeMyPassword,
  createAddress,
  deleteAddress,
  getUser,
  listMyAddresses,
  listUserActivities,
  lockUser,
  newIdempotencyKey,
  profileUpdateBody,
  searchUsers,
  setDefaultAddress,
  unlockUser,
  updateAddress,
} from './userApi'

const originalAdapter = apiClient.defaults.adapter
let adapter

beforeEach(() => {
  bindSessionManager(undefined)
  adapter = vi.fn(async (config) => ({ data: { ok: true }, status: 200, config, headers: {} }))
  apiClient.defaults.adapter = adapter
})

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
})

const sent = () => adapter.mock.calls[0][0]
const header = (name) => sent().headers.get(name)

describe('address book API contract', () => {
  const address = { recipientName: 'An', phone: '0912345678' }

  it('lists addresses with a plain GET', async () => {
    await listMyAddresses()
    expect(sent().method).toBe('get')
    expect(sent().url).toBe('/api/users/me/addresses')
  })

  it('creates with POST and forwards the idempotency key', async () => {
    await createAddress(address, 'key-1')
    expect(sent().method).toBe('post')
    expect(sent().url).toBe('/api/users/me/addresses')
    expect(JSON.parse(sent().data)).toEqual(address)
    expect(header('Idempotency-Key')).toBe('key-1')
  })

  it('omits the idempotency header when no key is given', async () => {
    await createAddress(address)
    expect(header('Idempotency-Key')).toBeFalsy()
  })

  it('updates, deletes and sets default on the addressed resource', async () => {
    await updateAddress('01ABC', address, 'key-2')
    await deleteAddress('01ABC', 'key-3')
    await setDefaultAddress('01ABC', 'key-4')
    const [update, remove, makeDefault] = adapter.mock.calls.map(([config]) => config)
    expect([update.method, update.url]).toEqual(['put', '/api/users/me/addresses/01ABC'])
    expect([remove.method, remove.url]).toEqual(['delete', '/api/users/me/addresses/01ABC'])
    expect([makeDefault.method, makeDefault.url]).toEqual([
      'put',
      '/api/users/me/addresses/01ABC/default',
    ])
    expect(update.headers.get('Idempotency-Key')).toBe('key-2')
    expect(remove.headers.get('Idempotency-Key')).toBe('key-3')
    expect(makeDefault.headers.get('Idempotency-Key')).toBe('key-4')
  })

  it('escapes the address id in the path', async () => {
    await deleteAddress('a/b')
    expect(sent().url).toBe('/api/users/me/addresses/a%2Fb')
  })

  it('builds a trimmed request body without the default flag', () => {
    expect(
      addressRequestBody({
        recipientName: ' Nguyễn Minh Tuấn ',
        phone: ' 0905123456 ',
        province: 'Đà Nẵng',
        district: 'Hải Châu',
        ward: 'Thạch Thang',
        streetAddress: ' 54 Nguyễn Lương Bằng ',
        note: '   ',
        isDefault: true,
      }),
    ).toEqual({
      recipientName: 'Nguyễn Minh Tuấn',
      phone: '0905123456',
      province: 'Đà Nẵng',
      district: 'Hải Châu',
      ward: 'Thạch Thang',
      streetAddress: '54 Nguyễn Lương Bằng',
      note: null,
    })
  })
})

describe('change password API contract', () => {
  it('sends only the two passwords and never an idempotency key', async () => {
    await changeMyPassword({
      currentPassword: 'Old1234@',
      newPassword: 'New1234@',
      confirmPassword: 'New1234@',
    })
    expect(sent().method).toBe('put')
    expect(sent().url).toBe('/api/users/me/password')
    expect(JSON.parse(sent().data)).toEqual({
      currentPassword: 'Old1234@',
      newPassword: 'New1234@',
    })
    expect(header('Idempotency-Key')).toBeFalsy()
  })
})

describe('admin user management API contract', () => {
  it('searches with trimmed keyword, status and paging, dropping empty filters', async () => {
    await searchUsers({ q: '  an  ', status: 'LOCKED', page: 2, size: 50 })
    expect(sent().url).toBe('/api/users')
    expect(sent().params).toEqual({ q: 'an', status: 'LOCKED', page: 2, size: 50 })
    adapter.mockClear()
    await searchUsers({ q: '  ', status: '' })
    expect(sent().params).toEqual({ q: undefined, status: undefined, page: 0, size: 20 })
  })

  it('reads one user and its activity page', async () => {
    await getUser('01JBQ9YDX7K3M8N5P2R4T6V8W0')
    await listUserActivities('01JBQ9YDX7K3M8N5P2R4T6V8W0', { page: 1, size: 10 })
    const [detail, activities] = adapter.mock.calls.map(([config]) => config)
    expect(detail.url).toBe('/api/users/01JBQ9YDX7K3M8N5P2R4T6V8W0')
    expect(activities.url).toBe('/api/users/01JBQ9YDX7K3M8N5P2R4T6V8W0/activities')
    expect(activities.params).toEqual({ page: 1, size: 10 })
  })

  it('locks with a trimmed reason and unlocks without a body', async () => {
    await lockUser('01JBQ9YDX7K3M8N5P2R4T6V8W0', '  Spam đánh giá  ', 'key-lock')
    await unlockUser('01JBQ9YDX7K3M8N5P2R4T6V8W0', 'key-unlock')
    const [lock, unlock] = adapter.mock.calls.map(([config]) => config)
    expect([lock.method, lock.url]).toEqual(['put', '/api/users/01JBQ9YDX7K3M8N5P2R4T6V8W0/lock'])
    expect(JSON.parse(lock.data)).toEqual({ reason: 'Spam đánh giá' })
    expect(lock.headers.get('Idempotency-Key')).toBe('key-lock')
    expect([unlock.method, unlock.url]).toEqual([
      'put',
      '/api/users/01JBQ9YDX7K3M8N5P2R4T6V8W0/unlock',
    ])
    expect(unlock.data).toBeUndefined()
    expect(unlock.headers.get('Idempotency-Key')).toBe('key-unlock')
  })
})

describe('newIdempotencyKey', () => {
  it('returns a different non-empty key every time', () => {
    const keys = new Set(Array.from({ length: 20 }, newIdempotencyKey))
    expect(keys.size).toBe(20)
    expect([...keys].every((key) => key.length > 0 && key.length <= 128)).toBe(true)
  })
})

describe('profileUpdateBody', () => {
  const current = {
    avatarUrl: 'https://example.com/an.png',
    dateOfBirth: '2000-01-01',
    gender: 'OTHER',
  }

  it('keeps the stored birth date and gender when the caller does not edit them', () => {
    expect(profileUpdateBody(current, { fullName: ' An ', phone: ' 0912345678 ' })).toEqual({
      fullName: 'An',
      phone: '0912345678',
      avatarUrl: current.avatarUrl,
      dateOfBirth: '2000-01-01',
      gender: 'OTHER',
    })
  })

  it('replaces them when edited and clears them when emptied, never touching the avatar', () => {
    expect(
      profileUpdateBody(current, {
        fullName: 'An',
        phone: '',
        dateOfBirth: '2001-02-03',
        gender: 'MALE',
      }),
    ).toEqual({
      fullName: 'An',
      phone: null,
      avatarUrl: current.avatarUrl,
      dateOfBirth: '2001-02-03',
      gender: 'MALE',
    })
    expect(
      profileUpdateBody(current, { fullName: 'An', dateOfBirth: '', gender: '' }),
    ).toMatchObject({ dateOfBirth: null, gender: null, avatarUrl: current.avatarUrl })
  })
})
