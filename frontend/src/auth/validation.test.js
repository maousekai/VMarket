import { describe, expect, it } from 'vitest'
import {
  validateAddress,
  validateChangePassword,
  validateLockReason,
  validateLogin,
  validateProfile,
  validateRegistration,
  validateResetPassword,
  validateOtp,
  validateUserSearch,
} from './validation'

describe('form validation against backend contracts', () => {
  it('allows existing passwords during login without enforcing new-password rules', () => {
    expect(validateLogin({ email: 'an@example.com', password: 'old' })).toEqual({})
    expect(validateLogin({ email: ' An@Example.com ', password: 'old' })).toEqual({})
    expect(validateLogin({ email: 'An.Nguyen', password: 'old' })).toHaveProperty('email')
    expect(validateLogin({ email: '', password: '' })).toHaveProperty('email')
  })

  it('rejects invalid usernames, weak passwords, and mismatching confirmation', () => {
    const errors = validateRegistration({
      email: 'an@example.com',
      username: 'x',
      password: 'abcdefg',
      confirmPassword: 'different',
      fullName: 'An',
      phone: '123',
    })
    expect(Object.keys(errors).sort()).toEqual(['confirmPassword', 'password', 'phone', 'username'])
    expect(
      validateRegistration({
        email: 'an@example.com',
        username: 'an.nguyen',
        password: 'Abcd1234@',
        confirmPassword: 'Abcd1234@',
        fullName: 'An',
        phone: '+84912345678',
      }),
    ).toEqual({})
  })

  it('accepts leading-zero six-digit codes and validates reset password rules', () => {
    expect(validateOtp({ email: 'an@example.com', otp: '001234' })).toEqual({})
    expect(validateOtp({ email: 'an@example.com', otp: '12345' })).toHaveProperty('otp')
    expect(
      validateResetPassword({
        email: 'an@example.com',
        code: '001234',
        newPassword: 'Abcd1234@',
        confirmPassword: 'Abcd1234@',
      }),
    ).toEqual({})
  })

  it('accepts optional profile fields and enforces Vietnamese phone format and name limit', () => {
    expect(validateProfile({ fullName: '', phone: '' })).toEqual({})
    expect(validateProfile({ fullName: 'a'.repeat(101), phone: '+84123' })).toHaveProperty(
      'fullName',
    )
    expect(validateProfile({ fullName: 'An', phone: '0912345678' })).toEqual({})
  })
})

describe('profile date of birth and gender', () => {
  const today = new Date(2026, 9, 21)

  it('accepts empty, past dates and every backend gender value', () => {
    expect(validateProfile({ dateOfBirth: '', gender: '' }, today)).toEqual({})
    expect(validateProfile({ dateOfBirth: '1998-08-12', gender: 'UNDISCLOSED' }, today)).toEqual({})
    for (const gender of ['MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED'])
      expect(validateProfile({ gender }, today)).toEqual({})
  })

  it('rejects today, future, impossible dates and unknown genders', () => {
    expect(validateProfile({ dateOfBirth: '2026-10-21' }, today)).toHaveProperty('dateOfBirth')
    expect(validateProfile({ dateOfBirth: '2027-01-01' }, today)).toHaveProperty('dateOfBirth')
    expect(validateProfile({ dateOfBirth: '2026-10-20' }, today)).toEqual({})
    expect(validateProfile({ dateOfBirth: '2001-02-30' }, today)).toHaveProperty('dateOfBirth')
    expect(validateProfile({ dateOfBirth: '12/08/1998' }, today)).toHaveProperty('dateOfBirth')
    expect(validateProfile({ gender: 'ROBOT' }, today)).toHaveProperty('gender')
  })
})

describe('address validation against AddressRequest', () => {
  const valid = {
    recipientName: 'Nguyễn Minh Tuấn',
    phone: '0905123456',
    province: 'Đà Nẵng',
    district: 'Hải Châu',
    ward: 'Thạch Thang',
    streetAddress: '54 Nguyễn Lương Bằng',
    note: '',
  }

  it('accepts a complete address with or without a note', () => {
    expect(validateAddress(valid)).toEqual({})
    expect(
      validateAddress({ ...valid, phone: '+84905123456', note: 'Gọi trước khi giao' }),
    ).toEqual({})
  })

  it('requires every field except the note, treating blanks as missing', () => {
    const errors = validateAddress({ recipientName: '  ', phone: '' })
    expect(Object.keys(errors).sort()).toEqual([
      'district',
      'phone',
      'province',
      'recipientName',
      'streetAddress',
      'ward',
    ])
  })

  it('rejects landline, spaced and malformed phone numbers', () => {
    for (const phone of ['0905 123 456', '02363123456', '12345', '+8490512345'])
      expect(validateAddress({ ...valid, phone })).toHaveProperty('phone')
  })

  it('enforces the backend length limits', () => {
    expect(validateAddress({ ...valid, recipientName: 'a'.repeat(101) })).toHaveProperty(
      'recipientName',
    )
    expect(validateAddress({ ...valid, streetAddress: 'a'.repeat(256) })).toHaveProperty(
      'streetAddress',
    )
    expect(validateAddress({ ...valid, streetAddress: 'a'.repeat(255) })).toEqual({})
    expect(validateAddress({ ...valid, note: 'a'.repeat(256) })).toHaveProperty('note')
  })
})

describe('change password validation', () => {
  const valid = {
    currentPassword: 'Abcd1234@',
    newPassword: 'Xyz98765#',
    confirmPassword: 'Xyz98765#',
  }

  it('accepts a valid change', () => {
    expect(validateChangePassword(valid)).toEqual({})
  })

  it('does not apply the new-password policy to the current password', () => {
    expect(validateChangePassword({ ...valid, currentPassword: 'legacy' })).toEqual({})
    expect(validateChangePassword({ ...valid, currentPassword: '' })).toHaveProperty(
      'currentPassword',
    )
    expect(validateChangePassword({ ...valid, currentPassword: 'a'.repeat(73) })).toHaveProperty(
      'currentPassword',
    )
  })

  it('rejects weak, unchanged and mismatching new passwords', () => {
    expect(validateChangePassword({ ...valid, newPassword: 'abcdefgh' })).toHaveProperty(
      'newPassword',
    )
    expect(
      validateChangePassword({
        currentPassword: 'Abcd1234@',
        newPassword: 'Abcd1234@',
        confirmPassword: 'Abcd1234@',
      }),
    ).toHaveProperty('newPassword')
    expect(validateChangePassword({ ...valid, confirmPassword: 'Other1234@' })).toHaveProperty(
      'confirmPassword',
    )
  })
})

describe('admin form validation', () => {
  it('limits the search keyword to 100 characters', () => {
    expect(validateUserSearch({ q: '' })).toEqual({})
    expect(validateUserSearch({ q: 'a'.repeat(100) })).toEqual({})
    expect(validateUserSearch({ q: 'a'.repeat(101) })).toHaveProperty('q')
  })

  it('requires a lock reason of at most 500 characters', () => {
    expect(validateLockReason({ reason: '   ' })).toHaveProperty('reason')
    expect(validateLockReason({ reason: 'Spam đánh giá' })).toEqual({})
    expect(validateLockReason({ reason: 'a'.repeat(501) })).toHaveProperty('reason')
  })
})
