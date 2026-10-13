import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function Layout() {
  const { status, user, logout } = useAuth()
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          RideX
        </Link>
        <nav>
          {status === 'authenticated' && user ? (
            <>
              <span className="muted">
                {user.fullName} · {user.role}
              </span>
              <button type="button" className="link-button" onClick={handleLogout}>
                Log out
              </button>
            </>
          ) : (
            <>
              <Link to="/login">Log in</Link>
              <Link to="/register">Sign up</Link>
            </>
          )}
        </nav>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
