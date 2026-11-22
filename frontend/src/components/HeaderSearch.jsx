import { useContext, useEffect, useId, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ACTIVE_CITY } from '../data/city'
import { AuthContext } from '../auth/useAuth'
import { SEARCH_LIMITS, suggestKeywords } from '../services/api/searchApi'
import { addRecentSearch, readRecentSearches, removeRecentSearch } from '../utils/recentSearches'
import { useImageSearch } from './imageSearchContext'
import searchIcon from '../assets/home/search.svg'
import searchButtonIcon from '../assets/home/search-button.svg'
import './search.css'

// Cho nguoi dung ngung go 300 ms roi moi goi goi y (NFR-PERF-02: goi y <= 300 ms phia server).
const SUGGESTION_DEBOUNCE_MS = 300

function currentKeyword(location) {
  return location.pathname === '/search' ? new URLSearchParams(location.search).get('q') || '' : ''
}

export function CameraIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width="18"
      height="18"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M4 8.5A2.5 2.5 0 0 1 6.5 6h1.2l1.3-2h6l1.3 2h1.2A2.5 2.5 0 0 1 20 8.5v8a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5z" />
      <circle cx="12" cy="12.5" r="3.5" />
    </svg>
  )
}

/** Goi y theo tu khoa, chi chay khi o tim kiem dang mo va tu khoa du 2 ky tu. */
function useSuggestions(keyword, enabled) {
  const value = keyword.trim()
  const active = enabled && value.length >= SEARCH_LIMITS.minSuggestionLength
  const [state, setState] = useState({ keyword: '', status: 'idle', items: [] })

  useEffect(() => {
    if (!active) return undefined
    const controller = new AbortController()
    const timer = setTimeout(() => {
      setState({ keyword: value, status: 'loading', items: [] })
      suggestKeywords(value, { signal: controller.signal }).then(
        (items) => setState({ keyword: value, status: 'ready', items }),
        (error) => {
          if (!controller.signal.aborted && error?.code !== 'ERR_CANCELED') {
            setState({ keyword: value, status: 'error', items: [] })
          }
        },
      )
    }, SUGGESTION_DEBOUNCE_MS)
    // Go tiep: huy hen gio va huy request cu de ket qua cu khong de len ket qua moi.
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [active, value])

  if (!active) return { status: 'idle', items: [] }
  // Dang cho debounce cho tu khoa moi: coi nhu dang tai, khong hien goi y cua tu khoa cu.
  if (state.keyword !== value) return { status: 'loading', items: [] }
  return state
}

export default function HeaderSearch() {
  const location = useLocation()
  const navigate = useNavigate()
  const auth = useContext(AuthContext)
  const userId = auth?.session?.userId || null
  const imageSearch = useImageSearch()
  const listId = useId()
  const container = useRef(null)
  const input = useRef(null)
  const [keyword, setKeyword] = useState(() => currentKeyword(location))
  const [syncedFrom, setSyncedFrom] = useState(location.key)
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(-1)
  const [recent, setRecent] = useState(() => readRecentSearches(userId))
  const [recentOwner, setRecentOwner] = useState(userId)

  // Keep the box in step with back/forward navigation between searches.
  if (syncedFrom !== location.key) {
    setSyncedFrom(location.key)
    setKeyword(currentKeyword(location))
    setOpen(false)
  }
  // Dang nhap / dang xuat: doi sang lich su cua tai khoan moi.
  if (recentOwner !== userId) {
    setRecentOwner(userId)
    setRecent(readRecentSearches(userId))
  }

  const trimmed = keyword.trim()
  const typing = trimmed.length >= SEARCH_LIMITS.minSuggestionLength
  const suggestions = useSuggestions(keyword, open)
  const options = typing
    ? suggestions.items.map((item) => ({ type: 'product', id: item.id, label: item.name }))
    : recent.map((value) => ({ type: 'recent', id: value, label: value }))
  const showPanel = open && (typing || recent.length > 0)
  const optionId = (index) => `${listId}-option-${index}`

  function close() {
    setOpen(false)
    setActiveIndex(-1)
  }

  function search(value) {
    const query = value.trim()
    if (query) setRecent(addRecentSearch(userId, query))
    close()
    input.current?.blur()
    navigate(query ? `/search?q=${encodeURIComponent(query)}` : '/search')
  }

  function choose(option) {
    if (option.type === 'recent') {
      setKeyword(option.label)
      search(option.label)
      return
    }
    if (trimmed) setRecent(addRecentSearch(userId, trimmed))
    close()
    input.current?.blur()
    navigate(`/products/${encodeURIComponent(option.id)}`)
  }

  function onKeyDown(event) {
    if (event.key === 'Escape') {
      if (open) {
        event.preventDefault()
        close()
      }
      return
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      if (event.key === 'Enter' && showPanel && activeIndex >= 0 && options[activeIndex]) {
        event.preventDefault()
        choose(options[activeIndex])
      }
      return
    }
    event.preventDefault()
    if (!open) {
      setOpen(true)
      return
    }
    if (!options.length) return
    // Vong qua cac lua chon, -1 = quay ve o nhap (giu tu khoa dang go).
    const step = event.key === 'ArrowDown' ? 1 : -1
    setActiveIndex((index) => {
      const next = index + step
      if (next >= options.length) return -1
      if (next < -1) return options.length - 1
      return next
    })
  }

  return (
    <form
      className="header-search"
      role="search"
      ref={container}
      onSubmit={(event) => {
        event.preventDefault()
        search(keyword)
      }}
      onBlur={(event) => {
        if (!container.current?.contains(event.relatedTarget)) close()
      }}
    >
      <div className="header-search__field">
        <img className="header-search__icon" src={searchIcon} alt="" width="16" height="16" />
        <label className="visually-hidden" htmlFor={`${listId}-input`}>
          Tìm kiếm sản phẩm
        </label>
        <input
          ref={input}
          id={`${listId}-input`}
          type="search"
          name="q"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showPanel}
          aria-controls={listId}
          aria-activedescendant={showPanel && activeIndex >= 0 ? optionId(activeIndex) : undefined}
          value={keyword}
          onChange={(event) => {
            setKeyword(event.target.value)
            setActiveIndex(-1)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          placeholder={`Tìm kiếm sản phẩm, thương hiệu tại ${ACTIVE_CITY.name}...`}
          maxLength={100}
          autoComplete="off"
          enterKeyHint="search"
        />
        {imageSearch && (
          <button
            type="button"
            className="header-search__camera"
            aria-label="Tìm bằng ảnh"
            title="Tìm bằng ảnh"
            onClick={() => {
              close()
              imageSearch.open()
            }}
          >
            <CameraIcon />
          </button>
        )}
      </div>
      <button className="header-search__submit" type="submit">
        <img src={searchButtonIcon} alt="" width="15" height="15" />
        Tìm
      </button>

      {showPanel && (
        // preventDefault on mousedown keeps focus in the input so a click is not a blur.
        <div className="search-suggest" onMouseDown={(event) => event.preventDefault()}>
          <p className="search-suggest__heading" id={`${listId}-heading`}>
            {typing ? 'Gợi ý nhanh' : 'Tìm kiếm gần đây'}
          </p>
          <ul
            id={listId}
            role="listbox"
            aria-labelledby={`${listId}-heading`}
            className={typing ? 'search-suggest__list' : 'search-suggest__chips'}
          >
            {options.map((option, index) => (
              <li
                key={`${option.type}-${option.id}`}
                id={optionId(index)}
                role="option"
                aria-selected={index === activeIndex}
                className={typing ? 'search-suggest__item' : 'search-suggest__chip'}
                onClick={() => choose(option)}
                onMouseEnter={() => setActiveIndex(index)}
              >
                <span>{option.label}</span>
                {option.type === 'recent' ? (
                  <button
                    type="button"
                    className="search-suggest__remove"
                    aria-label={`Xóa “${option.label}” khỏi tìm kiếm gần đây`}
                    tabIndex={-1}
                    onClick={(event) => {
                      event.stopPropagation()
                      setRecent(removeRecentSearch(userId, option.label))
                      setActiveIndex(-1)
                    }}
                  >
                    ×
                  </button>
                ) : (
                  <span className="search-suggest__arrow" aria-hidden="true">
                    →
                  </span>
                )}
              </li>
            ))}
          </ul>
          {typing && suggestions.status === 'loading' && (
            <p className="search-suggest__note" role="status">
              Đang tìm gợi ý…
            </p>
          )}
          {typing &&
            (suggestions.status === 'error' ||
              (suggestions.status === 'ready' && options.length === 0)) && (
              <div className="search-suggest__empty" role="status">
                <p>
                  <strong>Chưa có gợi ý phù hợp.</strong> Thử từ khóa khác hoặc tìm trực tiếp.
                </p>
                <button type="button" className="text-button" onClick={() => search(keyword)}>
                  Xem kết quả tìm kiếm cho “{trimmed}”
                </button>
              </div>
            )}
        </div>
      )}
    </form>
  )
}
