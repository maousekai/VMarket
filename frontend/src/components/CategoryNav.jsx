import { Link, useLocation } from 'react-router-dom'
import { useTopCategories } from '../hooks/useCatalog'

export default function CategoryNav() {
  const { status, data } = useTopCategories()
  const location = useLocation()
  const activeId =
    location.pathname === '/search' ? new URLSearchParams(location.search).get('categoryId') : null

  // The strip is a shortcut, not essential navigation: hide it rather than show an error.
  if (status === 'error' || (status === 'ready' && !data.length)) return null

  return (
    <nav className="category-nav" aria-label="Danh mục sản phẩm" aria-busy={status === 'loading'}>
      <ul className="category-nav__list">
        {status === 'ready' &&
          data.map((category) => (
            <li key={category.id}>
              <Link
                className="category-nav__link"
                to={`/search?categoryId=${encodeURIComponent(category.id)}`}
                aria-current={activeId === category.id ? 'page' : undefined}
              >
                {category.name}
              </Link>
            </li>
          ))}
      </ul>
    </nav>
  )
}
