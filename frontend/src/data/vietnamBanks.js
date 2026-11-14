// Ngân hàng tại Việt Nam nhận chuyển khoản qua VietQR / Napas 247. `code` là mã VietQR được
// lưu ở shop-service; `shortName` là tên viết tắt quen dùng.
export const VIETNAM_BANKS = [
  { code: 'VCB', shortName: 'Vietcombank', name: 'Ngân hàng TMCP Ngoại thương Việt Nam' },
  { code: 'BIDV', shortName: 'BIDV', name: 'Ngân hàng TMCP Đầu tư và Phát triển Việt Nam' },
  { code: 'ICB', shortName: 'VietinBank', name: 'Ngân hàng TMCP Công thương Việt Nam' },
  {
    code: 'VBA',
    shortName: 'Agribank',
    name: 'Ngân hàng Nông nghiệp và Phát triển Nông thôn Việt Nam',
  },
  { code: 'TCB', shortName: 'Techcombank', name: 'Ngân hàng TMCP Kỹ thương Việt Nam' },
  { code: 'MB', shortName: 'MB Bank', name: 'Ngân hàng TMCP Quân đội' },
  { code: 'ACB', shortName: 'ACB', name: 'Ngân hàng TMCP Á Châu' },
  { code: 'VPB', shortName: 'VPBank', name: 'Ngân hàng TMCP Việt Nam Thịnh Vượng' },
  { code: 'TPB', shortName: 'TPBank', name: 'Ngân hàng TMCP Tiên Phong' },
  { code: 'STB', shortName: 'Sacombank', name: 'Ngân hàng TMCP Sài Gòn Thương Tín' },
  {
    code: 'HDB',
    shortName: 'HDBank',
    name: 'Ngân hàng TMCP Phát triển Thành phố Hồ Chí Minh',
  },
  { code: 'VIB', shortName: 'VIB', name: 'Ngân hàng TMCP Quốc tế Việt Nam' },
  { code: 'SHB', shortName: 'SHB', name: 'Ngân hàng TMCP Sài Gòn - Hà Nội' },
  { code: 'EIB', shortName: 'Eximbank', name: 'Ngân hàng TMCP Xuất Nhập khẩu Việt Nam' },
  { code: 'MSB', shortName: 'MSB', name: 'Ngân hàng TMCP Hàng Hải Việt Nam' },
  { code: 'OCB', shortName: 'OCB', name: 'Ngân hàng TMCP Phương Đông' },
  { code: 'SEAB', shortName: 'SeABank', name: 'Ngân hàng TMCP Đông Nam Á' },
  { code: 'LPB', shortName: 'LPBank', name: 'Ngân hàng TMCP Lộc Phát Việt Nam' },
  { code: 'SCB', shortName: 'SCB', name: 'Ngân hàng TMCP Sài Gòn' },
  { code: 'NAB', shortName: 'Nam A Bank', name: 'Ngân hàng TMCP Nam Á' },
  { code: 'ABB', shortName: 'ABBANK', name: 'Ngân hàng TMCP An Bình' },
  { code: 'BAB', shortName: 'Bac A Bank', name: 'Ngân hàng TMCP Bắc Á' },
  { code: 'VCCB', shortName: 'BVBank', name: 'Ngân hàng TMCP Bản Việt' },
  { code: 'PGB', shortName: 'PGBank', name: 'Ngân hàng TMCP Thịnh vượng và Phát triển' },
  { code: 'KLB', shortName: 'KienlongBank', name: 'Ngân hàng TMCP Kiên Long' },
  { code: 'SGICB', shortName: 'SaigonBank', name: 'Ngân hàng TMCP Sài Gòn Công Thương' },
  { code: 'PVCB', shortName: 'PVcomBank', name: 'Ngân hàng TMCP Đại Chúng Việt Nam' },
  { code: 'NCB', shortName: 'NCB', name: 'Ngân hàng TMCP Quốc Dân' },
  { code: 'VAB', shortName: 'VietABank', name: 'Ngân hàng TMCP Việt Á' },
  { code: 'VIETBANK', shortName: 'Vietbank', name: 'Ngân hàng TMCP Việt Nam Thương Tín' },
  { code: 'BVB', shortName: 'BaoVietBank', name: 'Ngân hàng TMCP Bảo Việt' },
  { code: 'GPB', shortName: 'GPBank', name: 'Ngân hàng TNHH MTV Dầu Khí Toàn Cầu' },
  { code: 'OCEANBANK', shortName: 'OceanBank', name: 'Ngân hàng TNHH MTV Đại Dương' },
  { code: 'CBB', shortName: 'CBBank', name: 'Ngân hàng TNHH MTV Xây dựng Việt Nam' },
  { code: 'COOPBANK', shortName: 'Co-opBank', name: 'Ngân hàng Hợp tác xã Việt Nam' },
  { code: 'VRB', shortName: 'VRB', name: 'Ngân hàng Liên doanh Việt - Nga' },
  { code: 'IVB', shortName: 'Indovina', name: 'Ngân hàng TNHH Indovina' },
  { code: 'SHBVN', shortName: 'Shinhan Bank', name: 'Ngân hàng TNHH MTV Shinhan Việt Nam' },
  { code: 'WVN', shortName: 'Woori Bank', name: 'Ngân hàng TNHH MTV Woori Việt Nam' },
  { code: 'UOB', shortName: 'UOB', name: 'Ngân hàng TNHH MTV UOB Việt Nam' },
  { code: 'HSBC', shortName: 'HSBC', name: 'Ngân hàng TNHH MTV HSBC (Việt Nam)' },
  {
    code: 'SCVN',
    shortName: 'Standard Chartered',
    name: 'Ngân hàng TNHH MTV Standard Chartered Việt Nam',
  },
  { code: 'CIMB', shortName: 'CIMB', name: 'Ngân hàng TNHH MTV CIMB Việt Nam' },
  { code: 'HLBVN', shortName: 'Hong Leong Bank', name: 'Ngân hàng TNHH MTV Hong Leong Việt Nam' },
  { code: 'PBVN', shortName: 'Public Bank', name: 'Ngân hàng TNHH MTV Public Việt Nam' },
  { code: 'KBANK', shortName: 'KBank', name: 'Ngân hàng Đại chúng TNHH Kasikornbank' },
  { code: 'CAKE', shortName: 'CAKE by VPBank', name: 'Ngân hàng số CAKE by VPBank' },
  { code: 'UBANK', shortName: 'Ubank by VPBank', name: 'Ngân hàng số Ubank by VPBank' },
  { code: 'TIMO', shortName: 'Timo', name: 'Ngân hàng số Timo by Bản Việt Bank' },
]

/** Bỏ dấu, viết thường, bỏ ký tự không phải chữ / số để so khớp từ khoá. */
export function searchKey(text) {
  return (text || '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

const compactKey = (text) => searchKey(text).replace(/ /g, '')

const indexed = VIETNAM_BANKS.map((bank) => ({
  bank,
  code: bank.code.toLowerCase(),
  short: compactKey(bank.shortName),
  compact: compactKey(`${bank.code} ${bank.shortName}`),
  full: searchKey(`${bank.code} ${bank.shortName} ${bank.name}`),
}))

/**
 * Lọc theo tên viết tắt (VCB, Vietcombank, "mb bank") hoặc tên đầy đủ, không phân biệt dấu.
 * Thứ tự: mã / tên viết tắt bắt đầu bằng từ khoá, rồi tên viết tắt chứa từ khoá, rồi tên đầy đủ.
 */
export function searchBanks(query) {
  const key = searchKey(query)
  if (!key) return VIETNAM_BANKS
  const joined = key.replace(/ /g, '')
  const words = key.split(' ')
  const rank = ({ code, short, compact, full }) => {
    if (code.startsWith(joined) || short.startsWith(joined)) return 0
    if (compact.includes(joined)) return 1
    if (words.every((word) => full.includes(word))) return 2
    return -1
  }
  return indexed
    .map((entry) => ({ entry, score: rank(entry) }))
    .filter(({ score }) => score >= 0)
    .sort((a, b) => a.score - b.score)
    .map(({ entry }) => entry.bank)
}

export const findBank = (code) => VIETNAM_BANKS.find((bank) => bank.code === code) || null

export const bankLabel = (code) => {
  const bank = findBank(code)
  return bank ? `${bank.shortName} – ${bank.name}` : code || ''
}
