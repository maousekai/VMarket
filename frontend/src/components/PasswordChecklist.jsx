import { passwordRequirements } from '../auth/validation'

// Live feedback under the registration password field. Each rule is independent so
// users can see exactly what is still missing before submitting.
export default function PasswordChecklist({ id, password }) {
  return (
    <ul className="password-checklist" id={id} aria-label="Yêu cầu mật khẩu">
      {passwordRequirements(password).map(({ key, label, met }) => (
        <li key={key} className={`password-checklist__item${met ? ' is-met' : ''}`}>
          <span className="password-checklist__icon" aria-hidden="true">
            {met ? '✓' : '○'}
          </span>
          <span>{label}</span>
          <span className="password-checklist__status">{met ? '(đã đạt)' : '(chưa đạt)'}</span>
        </li>
      ))}
    </ul>
  )
}
