// Đơn vị hành chính cấp xã của Đà Nẵng cũ (trước khi sáp nhập Quảng Nam ngày 01/07/2025),
// theo tên mới sau sắp xếp của Nghị quyết 1659/NQ-UBTVQH15. Cấp quận/huyện đã bị bãi bỏ.
// Sao chép từ frontend/src/data/daNangAddress.js: giữ hai file đồng bộ.
export const DA_NANG_PROVINCE = 'Thành phố Đà Nẵng'

export interface WardGroup {
  label: string
  wards: string[]
}

export const DA_NANG_WARD_GROUPS: WardGroup[] = [
  {
    label: 'Phường',
    wards: [
      'Phường An Hải',
      'Phường An Khê',
      'Phường Cẩm Lệ',
      'Phường Hải Châu',
      'Phường Hải Vân',
      'Phường Hòa Cường',
      'Phường Hòa Khánh',
      'Phường Hòa Xuân',
      'Phường Liên Chiểu',
      'Phường Ngũ Hành Sơn',
      'Phường Sơn Trà',
      'Phường Thanh Khê',
    ],
  },
  // Huyện Hòa Vang cũ.
  { label: 'Xã', wards: ['Xã Bà Nà', 'Xã Hòa Tiến', 'Xã Hòa Vang'] },
]
