import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoginResponse } from './types'

// session.ts keeps module-level state (the in-flight refresh), so each test gets a fresh copy.
const post = vi.fn()
vi.mock('axios', () => ({
  default: {
    post: (...args: unknown[]) => post(...args),
    isAxiosError: (error: unknown) => Boolean((error as { isAxiosError?: boolean } | null)?.isAxiosError),
  },
}))

function loginResponse(refreshToken: string): { data: LoginResponse } {
  return {
    data: {
      accessToken: 'access-' + refreshToken,
      refreshToken,
      tokenType: 'Bearer',
      expiresInSeconds: 900,
      user: { id: 'u1', fullName: 'Asha Rao', email: 'asha@example.com', role: 'RIDER' },
    },
  }
}

async function load() {
  vi.resetModules()
  const session = await import('./session')
  const { tokenStorage } = await import('@/auth/tokenStorage')
  return { ...session, tokenStorage }
}

describe('refreshSession', () => {
  beforeEach(() => {
    post.mockReset()
  })

  it('sends ONE request when called several times at once (otherwise the server sees token reuse)', async () => {
    const { refreshSession, tokenStorage } = await load()
    tokenStorage.setTokens('old-access', 'refresh-1')
    post.mockResolvedValue(loginResponse('refresh-2'))

    const results = await Promise.all([refreshSession(), refreshSession(), refreshSession()])

    expect(post).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenCalledWith('/api/auth/refresh', { refreshToken: 'refresh-1' })
    expect(results[0]).toBe(results[1])
  })

  it('stores the rotated refresh token and announces the user', async () => {
    const { refreshSession, onSessionChange, tokenStorage } = await load()
    tokenStorage.setTokens('old-access', 'refresh-1')
    post.mockResolvedValue(loginResponse('refresh-2'))
    const listener = vi.fn()
    onSessionChange(listener)

    await refreshSession()

    expect(tokenStorage.getRefreshToken()).toBe('refresh-2')
    expect(tokenStorage.getAccessToken()).toBe('access-refresh-2')
    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ email: 'asha@example.com' }))
  })

  it('ends the session when the server rejects the token', async () => {
    const { refreshSession, onSessionChange, tokenStorage } = await load()
    tokenStorage.setTokens('old-access', 'refresh-1')
    post.mockRejectedValue({ isAxiosError: true, response: { status: 401 } })
    const listener = vi.fn()
    onSessionChange(listener)

    await expect(refreshSession()).rejects.toBeDefined()

    expect(tokenStorage.getRefreshToken()).toBeNull()
    expect(tokenStorage.getAccessToken()).toBeNull()
    expect(listener).toHaveBeenCalledWith(null)
  })

  it('keeps the token when the server cannot be reached, so the user can retry', async () => {
    const { refreshSession, tokenStorage } = await load()
    tokenStorage.setTokens('old-access', 'refresh-1')
    post.mockRejectedValue({ isAxiosError: true }) // no response: offline or server down

    await expect(refreshSession()).rejects.toBeDefined()

    expect(tokenStorage.getRefreshToken()).toBe('refresh-1')
  })

  it('does not call the server at all without a refresh token', async () => {
    const { refreshSession } = await load()

    await expect(refreshSession()).rejects.toThrow('No refresh token')

    expect(post).not.toHaveBeenCalled()
  })

  it('can refresh again after a refresh has finished', async () => {
    const { refreshSession, tokenStorage } = await load()
    tokenStorage.setTokens('old-access', 'refresh-1')
    post.mockResolvedValueOnce(loginResponse('refresh-2')).mockResolvedValueOnce(loginResponse('refresh-3'))

    await refreshSession()
    await refreshSession()

    expect(post).toHaveBeenCalledTimes(2)
    expect(post).toHaveBeenLastCalledWith('/api/auth/refresh', { refreshToken: 'refresh-2' })
  })
})
