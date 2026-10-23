import '../components/adminLayout.css'

// Sections that are in the sidebar but have no design or implementation yet stay empty.
export default function AdminPlaceholderPage({ section }) {
  return (
    <section className="admin-placeholder" aria-labelledby="admin-placeholder-title">
      <h1 id="admin-placeholder-title">{section.title}</h1>
      <p className="admin-placeholder__note">Chức năng này đang được phát triển.</p>
    </section>
  )
}
