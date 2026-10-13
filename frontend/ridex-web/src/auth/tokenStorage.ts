// Where the tokens live, and why:
//
// - The ACCESS token (15 min) is kept only in memory. A page reload loses it, but it is
//   recovered with the refresh token. Not being in storage means injected scripts cannot
//   simply read it from localStorage.
// - The REFRESH token (7 days) must survive reloads, so it goes in localStorage.
//   Trade-off: any XSS bug could read it. The production-grade alternative is an
//   HttpOnly + Secure + SameSite cookie set by the backend (needs backend + CSRF work);
//   we note this for the hardening pass. Rotation + reuse detection on the server limits
//   the damage of a stolen refresh token.

const REFRESH_KEY = 'ridex.refreshToken'

let accessToken: string | null = null

export const tokenStorage = {
  getAccessToken(): string | null {
    return accessToken
  },

  getRefreshToken(): string | null {
    try {
      return localStorage.getItem(REFRESH_KEY)
    } catch {
      return null // storage blocked (private mode, policy)
    }
  },

  setTokens(access: string, refresh: string): void {
    accessToken = access
    try {
      localStorage.setItem(REFRESH_KEY, refresh)
    } catch {
      // keep working for this tab without persistence
    }
  },

  clear(): void {
    accessToken = null
    try {
      localStorage.removeItem(REFRESH_KEY)
    } catch {
      // nothing to clean
    }
  },
}
