export const roleLabels = { BUYER: 'Buyer', SELLER: 'Seller', SHIPPER: 'Shipper', ADMIN: 'Admin' }

export const displayName = (user) => user.fullName || user.username
export const hasTemporaryLock = (user) =>
  Boolean(user.loginLockedUntil) && new Date(user.loginLockedUntil) > new Date()

export function activityText(activity) {
  const actor = activity.actorUsername || 'Admin'
  switch (activity.action) {
    case 'SUSPENDED':
      return `Admin ${actor} đã khóa tài khoản`
    case 'UNSUSPENDED':
      return `Admin ${actor} đã mở khóa tài khoản`
    case 'LOGIN_LOCKED':
      return 'Khóa tạm 15 phút do nhập sai mật khẩu 5 lần'
    case 'PASSWORD_CHANGED':
      return 'Người dùng đổi mật khẩu'
    case 'PASSWORD_RESET':
      return 'Đặt lại mật khẩu qua email'
    default:
      return activity.action
  }
}
