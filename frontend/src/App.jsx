import { Navigate, Routes, Route, Outlet } from 'react-router-dom'
import { AuthProvider } from './auth/AuthProvider'
import AppHeader from './components/AppHeader'
import RouteGuard from './components/RouteGuard'
import SiteFooter from './components/SiteFooter'
import HomePage from './pages/HomePage'
import SearchPage from './pages/SearchPage'
import ProductDetailPage from './pages/ProductDetailPage'
import AboutPage from './pages/AboutPage'
import NotFoundPage from './pages/NotFoundPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import VerifyEmailPage from './pages/VerifyEmailPage'
import ForgotPasswordPage from './pages/ForgotPasswordPage'
import ResetPasswordPage from './pages/ResetPasswordPage'
import ProfilePage from './pages/ProfilePage'
import AddressBookPage from './pages/AddressBookPage'
import AdminLayout from './components/AdminLayout'
import AdminNotFoundPage from './pages/AdminNotFoundPage'
import AdminPlaceholderPage from './pages/AdminPlaceholderPage'
import { adminSections } from './pages/adminSections'
import ChangePasswordPage from './pages/ChangePasswordPage'
import SellerPage from './pages/SellerPage'
import SellerRegisterPage from './pages/SellerRegisterPage'
import SellerSettingsPage from './pages/SellerSettingsPage'
import SellerDashboardPage from './pages/SellerDashboardPage'
import ShopPublicPage from './pages/ShopPublicPage'
import SellerLayout from './components/SellerLayout'
import RoleAreaPage from './pages/RoleAreaPage'

function ApplicationLayout() {
  return (
    <>
      <AppHeader />
      <Outlet />
      <SiteFooter />
    </>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route element={<RouteGuard roles={['ADMIN']} />}>
          <Route path="/admin" element={<AdminLayout />}>
            {adminSections.map(({ Component, ...section }) => {
              const element = Component ? <Component /> : <AdminPlaceholderPage section={section} />
              return section.path === '' ? (
                <Route key="overview" index element={element} />
              ) : (
                <Route key={section.path} path={section.path} element={element} />
              )
            })}
            <Route path="*" element={<AdminNotFoundPage />} />
          </Route>
        </Route>
        {/* The seller shell has its own top bar; keep it outside ApplicationLayout so the
            storefront header is not rendered above the seller header. */}
        <Route element={<RouteGuard />}>
          <Route path="/seller" element={<SellerLayout />}>
            <Route index element={<SellerPage />} />
            <Route path="dashboard" element={<SellerDashboardPage />} />
            <Route path="settings" element={<SellerSettingsPage />} />
          </Route>
        </Route>
        <Route element={<ApplicationLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/search" element={<SearchPage />} />
          <Route path="/products/:productId" element={<ProductDetailPage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route element={<RouteGuard />}>
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/profile/addresses" element={<AddressBookPage />} />
            <Route path="/profile/password" element={<ChangePasswordPage />} />
            <Route path="/seller/register" element={<SellerRegisterPage />} />
            <Route path="/seller/intro" element={<Navigate to="/seller" replace />} />
          </Route>
          <Route path="/shops/:shopId" element={<ShopPublicPage />} />
          <Route element={<RouteGuard roles={['SHIPPER']} />}>
            <Route path="/shipper" element={<RoleAreaPage role="SHIPPER" />} />
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </AuthProvider>
  )
}
