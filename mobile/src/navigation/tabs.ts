import type { TabItem } from '@/components/ui'

/**
 * Bottom-tab configuration. `match` lists URL paths (no group segments) that highlight the tab,
 * so e.g. app/(buyer)/orders/[id].tsx (path /orders/12) keeps "Tài khoản" highlighted.
 * Add your route folder here when you create one.
 */
export const BUYER_TABS: TabItem[] = [
  {
    route: '/(buyer)',
    label: 'Trang chủ',
    match: [
      '/',
      '/search',
      '/product',
      '/shop',
      '/category',
      '/image-search',
      '/assistant',
      '/notifications',
    ],
  },
  { route: '/(buyer)/cart', label: 'Giỏ hàng', match: ['/cart', '/checkout', '/payment'] },
  {
    route: '/(buyer)/account',
    label: 'Tài khoản',
    match: [
      '/account',
      '/orders',
      '/returns',
      '/reviews',
      '/addresses',
      '/profile',
      '/settings',
      '/chatbot',
    ],
  },
]

export const SHIPPER_TABS: TabItem[] = [
  { route: '/(shipper)', label: 'Đơn giao', match: ['/', '/tasks', '/delivery', '/notifications'] },
  { route: '/(shipper)/location', label: 'Vị trí', match: ['/location'] },
  { route: '/(shipper)/history', label: 'Lịch sử', match: ['/history'] },
]
