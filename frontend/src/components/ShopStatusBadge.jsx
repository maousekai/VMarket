import { shopStatusClass, shopStatusLabels } from '../utils/shop'

export default function ShopStatusBadge({ status }) {
  if (!status) return null
  return (
    <span className={`account-badge account-badge--${shopStatusClass[status] || 'pending'}`}>
      {shopStatusLabels[status] || status}
    </span>
  )
}
