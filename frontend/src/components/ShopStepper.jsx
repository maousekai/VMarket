const steps = [
  { key: 'submitted', label: 'Nộp hồ sơ', description: 'Thông tin gian hàng' },
  { key: 'review', label: 'Xét duyệt', description: 'VMarket kiểm tra hồ sơ' },
  { key: 'active', label: 'Hoạt động', description: 'Bắt đầu bán hàng' },
]

const stepState = {
  PENDING: 1,
  REJECTED: 0,
  ACTIVE: 2,
  SUSPENDED: 2,
}

export default function ShopStepper({ status }) {
  const current = stepState[status] ?? 0
  const rejected = status === 'REJECTED'
  const suspended = status === 'SUSPENDED'
  return (
    <ol className="shop-stepper" aria-label="Tiến trình hồ sơ gian hàng">
      {steps.map((step, index) => {
        const done = index < current || (index === 2 && status === 'ACTIVE')
        const active = index === current
        return (
          <li
            key={step.key}
            className={`shop-stepper__step${done ? ' is-done' : ''}${
              active ? ' is-active' : ''
            }${rejected && index === 0 ? ' is-rejected' : ''}${
              suspended && index === 2 ? ' is-suspended' : ''
            }`}
            aria-current={active ? 'step' : undefined}
          >
            <span className="shop-stepper__dot">{done ? '✓' : index + 1}</span>
            <span>
              <strong>{step.label}</strong>
              <small>{step.description}</small>
            </span>
          </li>
        )
      })}
    </ol>
  )
}
