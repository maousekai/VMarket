import { useId, useState } from 'react'
import eye from '../assets/auth/eye.png'

export default function FormField({ label, name, type = 'text', error, hint, ...props }) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  const password = type === 'password'
  const help = error || hint
  return (
    <div className="form-field">
      <label htmlFor={id}>{label}</label>
      <div className="form-field__control">
        <input
          id={id}
          name={name}
          type={password && visible ? 'text' : type}
          aria-invalid={Boolean(error)}
          aria-describedby={help ? `${id}-help` : undefined}
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
            <img src={eye} width="18" height="18" alt="" />
          </button>
        )}
      </div>
      {help && (
        <p id={`${id}-help`} className={error ? 'field-error' : 'field-hint'}>
          {help}
        </p>
      )}
    </div>
  )
}
