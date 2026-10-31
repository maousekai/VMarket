// Đơn vị hành chính cấp xã của Đà Nẵng CŨ (trước khi sáp nhập Quảng Nam ngày 01/07/2025),
// theo tên mới sau sắp xếp của Nghị quyết 1659/NQ-UBTVQH15. Cấp quận/huyện đã bị bãi bỏ.
//
// Cố ý KHÔNG có: các phường/xã thuộc Quảng Nam cũ (Tam Kỳ, Hội An, Điện Bàn, ...) và
// đặc khu Hoàng Sa. Muốn mở rộng thì thêm vào cuối nhóm tương ứng, giữ thứ tự chữ cái.
export const DA_NANG_PROVINCE = 'Thành phố Đà Nẵng'

export const DA_NANG_WARD_GROUPS = [
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

export const DA_NANG_WARDS = DA_NANG_WARD_GROUPS.flatMap((group) => group.wards)
