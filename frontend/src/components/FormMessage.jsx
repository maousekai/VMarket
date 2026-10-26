export default function FormMessage({ error, children, success = false }) {
  const content = error?.message || children
  if (!content) return null
  return (
    <div
      className={`form-message ${error ? 'form-message--error' : success ? 'form-message--success' : ''}`}
      role={error ? 'alert' : 'status'}
    >
      {content}
    </div>
  )
}
