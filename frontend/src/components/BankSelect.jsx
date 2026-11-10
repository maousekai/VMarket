import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { findBank, searchBanks } from '../data/vietnamBanks'
import './bankSelect.css'

const MAX_OPTIONS = 60

const optionLabel = (bank) => `${bank.shortName} – ${bank.name}`

/**
 * Chon ngan hang co o tim kiem: go ten viet tat (VCB, MB, Techcombank) hoac ten day du, khong can
 * dau. Combobox theo mau WAI-ARIA: mui ten len / xuong de di chuyen, Enter de chon, Esc de dong.
 */
export default function BankSelect({
  label = 'Ngân hàng',
  value,
  onChange,
  error,
  hint,
  required = false,
  disabled = false,
  name = 'bankCode',
}) {
  const id = useId()
  const listId = `${id}-list`
  const rootRef = useRef(null)
  const inputRef = useRef(null)
  const listRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const selected = findBank(value)
  const options = useMemo(() => searchBanks(query).slice(0, MAX_OPTIONS), [query])

  useEffect(() => {
    if (!open) return undefined
    const closeOutside = (event) => {
      if (!rootRef.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])

  useEffect(() => {
    if (!open) return
    listRef.current
      ?.querySelector(`[data-index="${active}"]`)
      ?.scrollIntoView?.({ block: 'nearest' })
  }, [active, open])

  function show() {
    if (disabled || open) return
    setQuery('')
    const index = selected ? searchBanks('').indexOf(selected) : 0
    setActive(Math.max(index, 0))
    setOpen(true)
  }

  function choose(bank) {
    onChange(bank.code)
    setOpen(false)
    setQuery('')
    inputRef.current?.focus()
  }

  function onKeyDown(event) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!open) return show()
      const step = event.key === 'ArrowDown' ? 1 : -1
      setActive((index) => Math.min(Math.max(index + step, 0), options.length - 1))
    } else if (event.key === 'Enter') {
      if (!open) return
      event.preventDefault()
      if (options[active]) choose(options[active])
    } else if (event.key === 'Escape' && open) {
      event.preventDefault()
      setOpen(false)
      setQuery('')
    } else if (event.key === 'Tab') {
      setOpen(false)
    }
  }

  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined

  return (
    <div className="account-field bank-select" ref={rootRef}>
      <label htmlFor={id}>
        {label}
        {required && <span aria-hidden="true"> *</span>}
      </label>
      <div className="bank-select__control">
        <input
          ref={inputRef}
          id={id}
          name={name}
          type="text"
          role="combobox"
          autoComplete="off"
          aria-autocomplete="list"
          aria-expanded={open}
          aria-controls={listId}
          aria-activedescendant={open && options[active] ? `${id}-opt-${active}` : undefined}
          aria-invalid={error ? 'true' : undefined}
          aria-required={required ? 'true' : undefined}
          aria-describedby={describedBy}
          disabled={disabled}
          placeholder={
            open && selected
              ? optionLabel(selected)
              : 'Tìm theo tên viết tắt hoặc tên đầy đủ (VD: VCB, Vietcombank)'
          }
          value={open ? query : selected ? optionLabel(selected) : ''}
          onFocus={show}
          onClick={show}
          onChange={(event) => {
            if (!open) setOpen(true)
            setQuery(event.target.value)
            setActive(0)
          }}
          onKeyDown={onKeyDown}
        />
        <svg
          className="bank-select__chevron"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          aria-hidden="true"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
      </div>
      {open && (
        <ul
          className="bank-select__list"
          id={listId}
          role="listbox"
          ref={listRef}
          aria-label={label}
        >
          {options.length === 0 && (
            <li className="bank-select__empty" role="presentation">
              Không tìm thấy ngân hàng phù hợp.
            </li>
          )}
          {options.map((bank, index) => (
            <li
              key={bank.code}
              id={`${id}-opt-${index}`}
              data-index={index}
              role="option"
              aria-selected={bank.code === value}
              className={`bank-select__option${index === active ? ' is-active' : ''}`}
              onPointerDown={(event) => event.preventDefault()}
              onClick={() => choose(bank)}
              onMouseEnter={() => setActive(index)}
            >
              <strong>
                {bank.shortName}
                {bank.shortName.toUpperCase() !== bank.code && <em> · {bank.code}</em>}
              </strong>
              <small>{bank.name}</small>
            </li>
          ))}
        </ul>
      )}
      {hint && !error && (
        <p className="account-field__hint" id={`${id}-hint`}>
          {hint}
        </p>
      )}
      {error && (
        <p className="account-field__error" id={`${id}-error`}>
          {error}
        </p>
      )}
    </div>
  )
}
