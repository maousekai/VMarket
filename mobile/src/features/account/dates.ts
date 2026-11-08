const pad = (n: number) => String(n).padStart(2, '0')

/** Today in Vietnam (UTC+7) as yyyy-MM-dd, so the birth-date boundary does not move with the device. */
function todayInVietnam(): string {
  return new Date(Date.now() + 7 * 3600 * 1000).toISOString().slice(0, 10)
}

/** '12/08/1998' -> '1998-08-12'; null when empty or not a real calendar date. */
export function displayToIso(value: string): string | null {
  const match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(value.trim())
  if (!match) return null
  const [day, month, year] = [Number(match[1]), Number(match[2]), Number(match[3])]
  const date = new Date(Date.UTC(year, month - 1, day))
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  )
    return null
  return `${year}-${pad(month)}-${pad(day)}`
}

/** '1998-08-12' -> '12/08/1998' */
export function isoToDisplay(value: string | null | undefined): string {
  const match = value ? /^(\d{4})-(\d{2})-(\d{2})/.exec(value) : null
  return match ? `${match[3]}/${match[2]}/${match[1]}` : ''
}

/** Inserts the slashes while the user types digits. */
export function maskDate(text: string): string {
  const digits = text.replace(/\D/g, '').slice(0, 8)
  if (digits.length <= 2) return digits
  if (digits.length <= 4) return `${digits.slice(0, 2)}/${digits.slice(2)}`
  return `${digits.slice(0, 2)}/${digits.slice(2, 4)}/${digits.slice(4)}`
}

export function dateOfBirthError(value: string): string | undefined {
  if (!value.trim()) return undefined
  const iso = displayToIso(value)
  if (!iso) return 'Ngày sinh không hợp lệ (dd/mm/yyyy).'
  if (iso >= todayInVietnam()) return 'Ngày sinh phải ở quá khứ.'
  return undefined
}
