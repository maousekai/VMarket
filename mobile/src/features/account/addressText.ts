import type { Address } from '@/api/user'
import { formatPhone } from '@/lib/format'

/** "Nguyễn Minh Tuấn · 0905 123 456" */
export const recipientLine = (a: Pick<Address, 'recipientName' | 'phone'>) =>
  `${a.recipientName} · ${formatPhone(a.phone)}`

/** "120 Hoàng Diệu, Phường Hải Châu" (district only when the old data has one) */
export const streetLine = (a: Pick<Address, 'streetAddress' | 'ward' | 'district'>) =>
  [a.streetAddress, a.ward, a.district].filter(Boolean).join(', ')

/** "120 Hoàng Diệu, Phường Hải Châu, Thành phố Đà Nẵng" */
export const fullAddressLine = (a: Address) =>
  [streetLine(a), a.province].filter(Boolean).join(', ')
