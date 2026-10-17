import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { Role } from '@/api/types'
import { AuthContext, type AuthContextValue, type AuthStatus } from './AuthContext'
import { ProtectedRoute, PublicOnlyRoute } from './RouteGuards'

function renderAt(path: string, status: AuthStatus, role?: Role) {
  const value: AuthContextValue = {
    status,
    user: role ? { id: 'u1', fullName: 'Asha Rao', email: 'asha@example.com', role } : null,
    login: () => Promise.reject(new Error('not used')),
    logout: () => Promise.resolve(),
  }
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<p>login page</p>} />
          <Route path="/forbidden" element={<p>forbidden page</p>} />
          <Route path="/dashboard" element={<p>dashboard page</p>} />
          <Route element={<ProtectedRoute />}>
            <Route path="/profile" element={<p>profile page</p>} />
          </Route>
          <Route element={<ProtectedRoute roles={['ADMIN']} />}>
            <Route path="/admin" element={<p>admin page</p>} />
          </Route>
          <Route element={<PublicOnlyRoute />}>
            <Route path="/register" element={<p>register page</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

describe('ProtectedRoute', () => {
  it('sends a logged-out visitor to the login page', () => {
    renderAt('/profile', 'anonymous')
    expect(screen.getByText('login page')).toBeInTheDocument()
  })

  it('waits while the session is being restored instead of redirecting', () => {
    renderAt('/profile', 'loading')
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(screen.queryByText('login page')).not.toBeInTheDocument()
  })

  it('lets any logged-in user into a route with no role requirement', () => {
    renderAt('/profile', 'authenticated', 'RIDER')
    expect(screen.getByText('profile page')).toBeInTheDocument()
  })

  it('shows the forbidden page when the role does not match', () => {
    renderAt('/admin', 'authenticated', 'RIDER')
    expect(screen.getByText('forbidden page')).toBeInTheDocument()
    expect(screen.queryByText('admin page')).not.toBeInTheDocument()
  })

  it('lets the right role in', () => {
    renderAt('/admin', 'authenticated', 'ADMIN')
    expect(screen.getByText('admin page')).toBeInTheDocument()
  })
})

describe('PublicOnlyRoute', () => {
  it('shows sign-up to a logged-out visitor', () => {
    renderAt('/register', 'anonymous')
    expect(screen.getByText('register page')).toBeInTheDocument()
  })

  it('sends a logged-in user to their dashboard', () => {
    renderAt('/register', 'authenticated', 'RIDER')
    expect(screen.getByText('dashboard page')).toBeInTheDocument()
  })
})
