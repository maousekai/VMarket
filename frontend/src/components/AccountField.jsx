import { useId, useState } from 'react'
import eye from '../assets/auth/eye.png'

// Labelled control for the account forms. `as` switches between input, select and textarea.
// Password inputs get the same show/hide button as the login and register forms.
export default function AccountField({
  label,
  error,
  hint,
  required = false,
  as: Control = 'input',
  type,
  children,
  ...props
}) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  const password = Control === 'input' && type === 'password'
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  const control = (
    <Control
      id={id}
      type={password && visible ? 'text' : type}
      aria-invalid={error ? 'true' : undefined}
      aria-required={required ? 'true' : undefined}
      aria-describedby={describedBy}
      {...props}
    >
      {children}
    </Control>
  )
  return (
    <div className="account-field">
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      {password ? (
        <div className="account-field__control">
          {control}
          <button
            className="password-toggle"
            type="button"
            aria-label={`${visible ? 'Ẩn' : 'Hiện'} ${label.toLowerCase()}`}
            aria-pressed={visible}
            onClick={() => setVisible(!visible)}
          >
            <img src={eye} width="18" height="18" alt="" />
          </button>
        </div>
      ) : (
        control
      )}
      {hint && !error && (
        <p className="account-field__hint" id={`${id}-hint`}>
          {hint}
        </p>
      )}
      {error && (
        <p className="account-field__error" id={`${id}-error`}>
          {error}
        </p>
      )}
    </div>
  )
}
