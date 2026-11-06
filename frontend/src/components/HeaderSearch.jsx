import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ACTIVE_CITY } from '../data/city'
import searchIcon from '../assets/home/search.svg'
import searchButtonIcon from '../assets/home/search-button.svg'

function currentKeyword(location) {
  return location.pathname === '/search' ? new URLSearchParams(location.search).get('q') || '' : ''
}

export default function HeaderSearch() {
  const location = useLocation()
  const navigate = useNavigate()
  const [keyword, setKeyword] = useState(() => currentKeyword(location))
  const [syncedFrom, setSyncedFrom] = useState(location.key)

  // Keep the box in step with back/forward navigation between searches.
  if (syncedFrom !== location.key) {
    setSyncedFrom(location.key)
    setKeyword(currentKeyword(location))
  }

  function onSubmit(event) {
    event.preventDefault()
    const query = keyword.trim()
    navigate(query ? `/search?q=${encodeURIComponent(query)}` : '/search')
  }

  return (
    <form className="header-search" role="search" onSubmit={onSubmit}>
      <label className="header-search__field">
        <img className="header-search__icon" src={searchIcon} alt="" width="16" height="16" />
        <span className="visually-hidden">Tìm kiếm sản phẩm</span>
        <input
          type="search"
          name="q"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder={`Tìm kiếm sản phẩm, thương hiệu tại ${ACTIVE_CITY.name}...`}
          maxLength={100}
          autoComplete="off"
          enterKeyHint="search"
        />
      </label>
      <button className="header-search__submit" type="submit">
        <img src={searchButtonIcon} alt="" width="15" height="15" />
        Tìm
      </button>
    </form>
  )
}
