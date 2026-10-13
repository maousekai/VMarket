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
        <Route element={<ApplicationLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route element={<RouteGuard />}>
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/seller" element={<SellerPage />} />
            <Route path="/seller/register" element={<SellerIntroPage />} />
          </Route>
          <Route element={<RouteGuard roles={['ADMIN']} />}>
            <Route path="/admin" element={<RoleAreaPage role="ADMIN" />} />
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
