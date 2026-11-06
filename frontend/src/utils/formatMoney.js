const formatters = new Map()

function formatter(currency) {
  if (!formatters.has(currency)) {
    formatters.set(
      currency,
      new Intl.NumberFormat('vi-VN', { style: 'currency', currency, currencyDisplay: 'symbol' }),
    )
  }
  return formatters.get(currency)
}

// Tien den tu backend la so nguyen don vi nho nhat + ma tien te. VND khong co phan le
// nen don vi nho nhat chinh la dong; cac tien te khac chia theo so chu so le cua no.
export function formatMoney(amount, currency = 'VND') {
  if (amount === null || amount === undefined || !Number.isFinite(Number(amount))) return ''
  const code = (currency || 'VND').toUpperCase()
  const fmt = formatter(code)
  const digits = fmt.resolvedOptions().maximumFractionDigits
  return fmt.format(Number(amount) / 10 ** digits)
}
