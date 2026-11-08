import type { Href } from 'expo-router'

/** Account-group routes. Casts keep this file independent of the generated typed-route list. */
export const accountRoutes = {
  home: '/(buyer)/account' as Href,
  profileEdit: '/(buyer)/account/profile-edit' as Href,
  changePassword: '/(buyer)/account/change-password' as Href,
  avatar: '/(buyer)/account/avatar' as Href,
  addressNew: '/(buyer)/account/address/new' as Href,
  addressEdit: (id: string) => `/(buyer)/account/address/${encodeURIComponent(id)}/edit` as Href,
  addressDelete: (id: string) =>
    `/(buyer)/account/address/${encodeURIComponent(id)}/delete` as Href,
  orders: '/orders' as Href,
  notifications: '/(buyer)/notifications' as Href,
  assistant: '/(buyer)/assistant' as Href,
  login: '/(auth)/login' as Href,
  register: '/(auth)/register' as Href,
  verifyEmail: '/(auth)/verify-email' as Href,
  forgotPassword: '/(auth)/forgot-password' as Href,
}
