import { describe, expect, it } from 'vitest'
import { activityText, displayName, hasTemporaryLock } from './adminUsers'

describe('admin user helpers', () => {
  it('falls back to the username when the profile has no name', () => {
    expect(displayName({ fullName: 'Nguyễn Văn Bình', username: 'binh' })).toBe('Nguyễn Văn Bình')
    expect(displayName({ fullName: null, username: 'binh' })).toBe('binh')
  })

  it('detects an active temporary login lock only', () => {
    const future = new Date(Date.now() + 3_600_000).toISOString()
    const past = new Date(Date.now() - 3_600_000).toISOString()
    expect(hasTemporaryLock({ loginLockedUntil: future })).toBe(true)
    expect(hasTemporaryLock({ loginLockedUntil: past })).toBe(false)
    expect(hasTemporaryLock({ loginLockedUntil: null })).toBe(false)
  })

  it('describes every known activity and passes unknown ones through', () => {
    expect(activityText({ action: 'SUSPENDED', actorUsername: 'root' })).toBe(
      'Admin root đã khóa tài khoản',
    )
    expect(activityText({ action: 'UNSUSPENDED', actorUsername: null })).toBe(
      'Admin Admin đã mở khóa tài khoản',
    )
    expect(activityText({ action: 'LOGIN_LOCKED' })).toContain('5 lần')
    expect(activityText({ action: 'PASSWORD_CHANGED' })).toBe('Người dùng đổi mật khẩu')
    expect(activityText({ action: 'PASSWORD_RESET' })).toBe('Đặt lại mật khẩu qua email')
    expect(activityText({ action: 'SOMETHING_NEW' })).toBe('SOMETHING_NEW')
  })
})
