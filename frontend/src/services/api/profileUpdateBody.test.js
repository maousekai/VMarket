import { describe, expect, it } from 'vitest'
import { profileUpdateBody } from './userApi'

describe('profileUpdateBody avatar handling', () => {
  const current = {
    avatarUrl: 'https://example.com/old.png',
    dateOfBirth: '2000-01-01',
    gender: 'OTHER',
  }

  it('keeps the stored avatar when the caller does not edit it', () => {
    expect(profileUpdateBody(current, { fullName: 'An' }).avatarUrl).toBe(current.avatarUrl)
    expect(profileUpdateBody({}, { fullName: 'An' }).avatarUrl).toBeNull()
  })

  it('replaces the avatar with the trimmed edited URL', () => {
    const body = profileUpdateBody(current, { avatarUrl: '  https://example.com/new.png  ' })
    expect(body.avatarUrl).toBe('https://example.com/new.png')
    expect(body.dateOfBirth).toBe('2000-01-01')
    expect(body.gender).toBe('OTHER')
  })

  it('clears the avatar when the edited value is empty', () => {
    expect(profileUpdateBody(current, { avatarUrl: '' }).avatarUrl).toBeNull()
    expect(profileUpdateBody(current, { avatarUrl: '   ' }).avatarUrl).toBeNull()
  })
})
