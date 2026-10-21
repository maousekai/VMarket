import { useEffect, useId, useRef } from 'react'

const focusable = 'a[href], button, input, select, textarea'

// Accessible modal dialog: focuses its first control, traps Tab, closes on Escape or backdrop.
export default function Modal({ title, description, onClose, children }) {
  const titleId = useId()
  const dialog = useRef(null)

  useEffect(() => {
    const previous = document.activeElement
    dialog.current?.querySelector(focusable)?.focus()
    return () => previous?.focus?.()
  }, [])

  function onKeyDown(event) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key !== 'Tab') return
    const items = [...dialog.current.querySelectorAll(focusable)].filter((item) => !item.disabled)
    if (!items.length) return
    const first = items[0]
    const last = items[items.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        ref={dialog}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId}>{title}</h2>
        {description && <p className="account-subtitle">{description}</p>}
        {children}
      </div>
    </div>
  )
}
