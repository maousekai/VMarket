import './pagination.css'

// Page numbers with ellipses. `page` is zero-based (Spring/Product Service pages).
function pageItems(page, totalPages) {
  const pages = new Set([0, totalPages - 1, page - 1, page, page + 1])
  const sorted = [...pages]
    .filter((value) => value >= 0 && value < totalPages)
    .sort((a, b) => a - b)
  const items = []
  sorted.forEach((value, index) => {
    if (index > 0 && value - sorted[index - 1] > 1) items.push(`gap-${value}`)
    items.push(value)
  })
  return items
}

export default function Pagination({ page = 0, totalPages = 0, onChange, label = 'Phân trang' }) {
  if (!totalPages || totalPages <= 1) return null
  return (
    <nav className="pagination" aria-label={label}>
      <button type="button" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        ‹ Trước
      </button>
      {pageItems(page, totalPages).map((item) =>
        typeof item === 'string' ? (
          <span key={item} className="pagination__gap" aria-hidden="true">
            …
          </span>
        ) : (
          <button
            key={item}
            type="button"
            aria-current={item === page ? 'page' : undefined}
            aria-label={`Trang ${item + 1}`}
            onClick={() => item !== page && onChange(item)}
          >
            {item + 1}
          </button>
        ),
      )}
      <button type="button" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>
        Tiếp ›
      </button>
    </nav>
  )
}
