import { Navigate, Route, Routes } from 'react-router-dom'
import { ProtectedRoute, PublicOnlyRoute } from './auth/RouteGuards'
import { Layout } from './components/Layout'
import { DashboardPage } from './pages/DashboardPage'
import { ForgotPasswordPage } from './pages/ForgotPasswordPage'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { ResetPasswordPage } from './pages/ResetPasswordPage'
import { RoleHomePage } from './pages/RoleHomePage'
import { ForbiddenPage, NotFoundPage } from './pages/StatusPages'
import { VerifyEmailPage } from './pages/VerifyEmailPage'

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        {/* Anyone may open these (the email links land here even if you are logged in) */}
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />

        {/* Only for logged-out visitors */}
        <Route element={<PublicOnlyRoute />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        </Route>

        {/* Any logged-in user */}
        <Route element={<ProtectedRoute />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/forbidden" element={<ForbiddenPage />} />
        </Route>

        {/* One area per role */}
        <Route element={<ProtectedRoute roles={['RIDER']} />}>
          <Route path="/rider" element={<RoleHomePage role="RIDER" />} />
        </Route>
        <Route element={<ProtectedRoute roles={['DRIVER']} />}>
          <Route path="/driver" element={<RoleHomePage role="DRIVER" />} />
        </Route>
        <Route element={<ProtectedRoute roles={['ADMIN']} />}>
          <Route path="/admin" element={<RoleHomePage role="ADMIN" />} />
        </Route>

        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default App
