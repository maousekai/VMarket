import { useId, useState } from 'react'
import PasswordToggleIcon from './PasswordToggleIcon'

// Labelled control for the account forms. `as` switches between input, select and textarea.
// Password inputs get the same show/hide button as the login and register forms.
export default function AccountField({
  label,
  error,
  hint,
  success = false,
  required = false,
  as: Control = 'input',
  type,
  children,
  ...props
}) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  const password = Control === 'input' && type === 'password'
  const hintClass = success ? 'account-field__success' : 'account-field__hint'
  const describedBy = error
    ? `${id}-error`
    : hint
      ? `${id}-${success ? 'success' : 'hint'}`
      : undefined
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
            <PasswordToggleIcon visible={visible} />
          </button>
        </div>
      ) : (
        control
      )}
      {hint && !error && (
        <p className={hintClass} id={`${id}-${success ? 'success' : 'hint'}`}>
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
