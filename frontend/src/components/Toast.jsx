import { useEffect, useState } from 'react'

// Small floating confirmation used after an action leaves the current page, such as
// signing out and landing on the login screen.
export default function Toast({ message, duration = 5000 }) {
  const [visible, setVisible] = useState(Boolean(message))

  useEffect(() => {
    setVisible(Boolean(message))
    if (!message) return undefined
    const timer = setTimeout(() => setVisible(false), duration)
    return () => clearTimeout(timer)
  }, [message, duration])

  if (!visible) return null
  return (
    <div className="toast" role="status" aria-live="polite">
      <span>{message}</span>
      <button type="button" aria-label="Đóng thông báo" onClick={() => setVisible(false)}>
        ×
      </button>
    </div>
  )
}
