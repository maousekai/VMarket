// Noi dung banner tinh cua trang chu (chua co CMS/khuyen mai tu backend).
export const homeBanners = [
  {
    id: 'fashion',
    title: ['Phong Cách Đà Nẵng', '— Mặc Gì Hôm Nay?'],
    description: 'Hàng nghìn mẫu thời trang local & quốc tế từ các nhà bán trong thành phố',
    cta: 'Mua Sắm Thời Trang',
    to: '/search?q=th%E1%BB%9Di%20trang',
  },
  {
    id: 'local-food',
    title: ['Hương Vị Đà Nẵng', '— Giao Tận Cửa Nhà'],
    description: 'Mì Quảng, chả bò, hải sản khô… từ những cửa hàng quen của người Đà Nẵng',
    cta: 'Khám Phá Ẩm Thực',
    to: '/search?q=%C4%91%E1%BA%B7c%20s%E1%BA%A3n',
  },
  {
    id: 'sell',
    title: ['Mở Gian Hàng', '— Bán Cho Cả Thành Phố'],
    description: 'Đăng ký gian hàng miễn phí, tiếp cận người mua tại 6 quận nội thành',
    cta: 'Đăng Ký Bán Hàng',
    to: '/seller/register',
  },
]
