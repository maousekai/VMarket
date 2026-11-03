import { useId, useState } from 'react'
import PasswordToggleIcon from './PasswordToggleIcon'

export default function FormField({
  label,
  name,
  type = 'text',
  error,
  hint,
  success = false,
  describedBy,
  ...props
}) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  const password = type === 'password'
  const help = error || hint
  const describedByIds = [help ? `${id}-help` : null, describedBy].filter(Boolean)
  return (
    <div className="form-field">
      <label htmlFor={id}>{label}</label>
      <div className="form-field__control">
        <input
          id={id}
          name={name}
          type={password && visible ? 'text' : type}
          aria-invalid={Boolean(error)}
          aria-describedby={describedByIds.length ? describedByIds.join(' ') : undefined}
          {...props}
        />
        {password && (
          <button
            className="password-toggle"
            type="button"
            aria-label={visible ? `Ẩn ${label.toLowerCase()}` : `Hiện ${label.toLowerCase()}`}
            aria-pressed={visible}
            onClick={() => setVisible(!visible)}
          >
            <PasswordToggleIcon visible={visible} />
          </button>
        )}
      </div>
      {help && (
        <p
          id={`${id}-help`}
          className={error ? 'field-error' : success ? 'field-success' : 'field-hint'}
        >
          {help}
        </p>
      )}
    </div>
  )
}
