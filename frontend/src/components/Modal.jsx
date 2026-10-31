import { useEffect, useId, useRef } from 'react'

const focusable = [
  'a[href]',
  'area[href]',
  'button',
  'input',
  'select',
  'textarea',
  'iframe',
  'object',
  'embed',
  '[contenteditable]',
  '[tabindex]',
].join(', ')

function isVisible(element) {
  if (element.hidden || element.getAttribute('aria-hidden') === 'true') return false
  const style = window.getComputedStyle(element)
  return style.display !== 'none' && style.visibility !== 'hidden'
}

function focusableItems(container) {
  if (!container) return []
  return [...container.querySelectorAll(focusable)].filter(
    (item) =>
      !item.disabled &&
      item.type !== 'hidden' &&
      item.tabIndex >= 0 &&
      !item.closest('[inert], [aria-hidden="true"]') &&
      isVisible(item),
  )
}

// Accessible modal dialog: focuses a safe control, traps Tab, closes on Escape or backdrop.
// `variant="drawer"` slides the panel in from the right edge instead of centring it.
export default function Modal({ title, description, onClose, variant = 'dialog', children }) {
  const drawer = variant === 'drawer'
  const titleId = useId()
  const dialog = useRef(null)

  useEffect(() => {
    const previous = document.activeElement
    // Never focus the first control by default: in confirmation dialogs that is often the
    // destructive action. Callers opt in with data-autofocus on a safe control if they want.
    const autoFocus = dialog.current?.querySelector('[data-autofocus]')
    const initial = autoFocus && !autoFocus.disabled ? autoFocus : dialog.current
    initial?.focus?.({ preventScroll: true })
    return () => previous?.focus?.()
  }, [])

  function onKeyDown(event) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key !== 'Tab') return
    const items = focusableItems(dialog.current)
    if (!items.length) {
      event.preventDefault()
      dialog.current?.focus()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement
    const outside = !dialog.current?.contains(active)
    if (event.shiftKey && (active === first || active === dialog.current || outside)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && (active === last || outside)) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div
      className={`modal-backdrop${drawer ? ' modal-backdrop--drawer' : ''}`}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        className={`modal${drawer ? ' modal--drawer' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        ref={dialog}
        tabIndex={-1}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId}>{title}</h2>
        {description && <p className="account-subtitle">{description}</p>}
        {children}
      </div>
    </div>
  )
}
