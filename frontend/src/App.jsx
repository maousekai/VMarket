import { Routes, Route, Outlet } from 'react-router-dom'
import { AuthProvider } from './auth/AuthProvider'
import AppHeader from './components/AppHeader'
import RouteGuard from './components/RouteGuard'
import HomePage from './pages/HomePage'
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
import AdminUsersPage from './pages/AdminUsersPage'
import { adminSections } from './pages/adminSections'
import ChangePasswordPage from './pages/ChangePasswordPage'
import SellerPage from './pages/SellerPage'
import SellerIntroPage from './pages/SellerIntroPage'
import RoleAreaPage from './pages/RoleAreaPage'

function ApplicationLayout() {
  return (
    <>
      <AppHeader />
      <Outlet />
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
            {adminSections.map((section) => {
              const element =
                section.path === 'users' ? (
                  <AdminUsersPage />
                ) : (
                  <AdminPlaceholderPage section={section} />
                )
              return section.path === '' ? (
                <Route key="overview" index element={element} />
              ) : (
                <Route key={section.path} path={section.path} element={element} />
              )
            })}
            <Route path="*" element={<AdminNotFoundPage />} />
          </Route>
        </Route>
        <Route element={<ApplicationLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route element={<RouteGuard />}>
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/profile/addresses" element={<AddressBookPage />} />
            <Route path="/profile/password" element={<ChangePasswordPage />} />
            <Route path="/seller" element={<SellerPage />} />
            <Route path="/seller/register" element={<SellerIntroPage />} />
          </Route>
          <Route element={<RouteGuard roles={['SHIPPER']} />}>
            <Route path="/shipper" element={<RoleAreaPage role="SHIPPER" />} />
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </AuthProvider>
  )
}
