// Sidebar sections of the admin area, in the order of the Figma design. `built` marks the
// sections that already have a real page; the others render an empty placeholder for now.
const section = (path, label, icon, title, built = false) => ({
  path,
  to: path ? `/admin/${path}` : '/admin',
  label,
  icon,
  title,
  built,
})

export const adminSections = [
  section('', 'Tổng quan', 'overview', 'Tổng quan nền tảng'),
  section('products', 'Kiểm duyệt sản phẩm', 'products', 'Kiểm duyệt sản phẩm & đánh giá'),
  section('orders', 'Giám sát đơn hàng', 'orders', 'Giám sát đơn hàng'),
  section('shops', 'Phê duyệt gian hàng', 'shops', 'Duyệt gian hàng'),
  section('shippers', 'Điều phối Shipper', 'shippers', 'Điều phối Shipper'),
  section('disputes', 'Phân xử khiếu nại', 'disputes', 'Phân xử khiếu nại'),
  section('users', 'Quản lý người dùng', 'users', 'Quản lý người dùng toàn sàn', true),
  section('categories', 'Quản lý danh mục', 'categories', 'Quản lý danh mục'),
  section('reports', 'Báo cáo & Tài chính', 'reports', 'Báo cáo & Tài chính'),
  section('settings', 'Cài đặt hệ thống', 'settings', 'Cài đặt hệ thống'),
]
