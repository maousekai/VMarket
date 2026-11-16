import AdminUsersPage from './AdminUsersPage'
import AdminShopsPage from './AdminShopsPage'
import AdminCategoriesPage from './AdminCategoriesPage'
import AdminProductsPage from './AdminProductsPage'
import AdminOrdersPage from './AdminOrdersPage'
import AdminDisputesPage from './AdminDisputesPage'
import AdminPaymentsPage from './AdminPaymentsPage'

// Sidebar sections of the admin area, in the order of the Figma design. `Component` is the
// real page when it exists; sections without one render an empty placeholder.
const section = (path, label, icon, title, Component = null) => ({
  path,
  to: path ? `/admin/${path}` : '/admin',
  label,
  icon,
  title,
  Component,
})

export const adminSections = [
  section('', 'Tổng quan', 'overview', 'Tổng quan nền tảng'),
  section('products', 'Kiểm duyệt sản phẩm', 'products', 'Kiểm duyệt sản phẩm', AdminProductsPage),
  section('orders', 'Giám sát đơn hàng', 'orders', 'Giám sát đơn hàng', AdminOrdersPage),
  section('shops', 'Phê duyệt gian hàng', 'shops', 'Duyệt gian hàng', AdminShopsPage),
  section('shippers', 'Điều phối Shipper', 'shippers', 'Điều phối Shipper'),
  section('disputes', 'Phân xử khiếu nại', 'disputes', 'Phân xử khiếu nại', AdminDisputesPage),
  section('users', 'Quản lý người dùng', 'users', 'Quản lý người dùng toàn sàn', AdminUsersPage),
  section('categories', 'Quản lý danh mục', 'categories', 'Quản lý danh mục', AdminCategoriesPage),
  section('reports', 'Báo cáo & Tài chính', 'reports', 'Thanh toán & hoàn tiền', AdminPaymentsPage),
  section('settings', 'Cài đặt hệ thống', 'settings', 'Cài đặt hệ thống'),
]
