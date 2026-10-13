import { describe, expect, it } from 'vitest'
import {
  validateLogin,
  validateProfile,
  validateRegistration,
  validateResetPassword,
  validateOtp,
} from './validation'

describe('form validation against backend contracts', () => {
  it('allows existing passwords during login without enforcing new-password rules', () => {
    expect(validateLogin({ email: 'an@example.com', password: 'old' })).toEqual({})
    expect(validateLogin({ email: 'bad-email', password: '' })).toHaveProperty('email')
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
