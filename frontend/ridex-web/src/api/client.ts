import axios, { type InternalAxiosRequestConfig } from 'axios'
import { tokenStorage } from '../auth/tokenStorage'
import { refreshSession } from './session'

// The vite dev server proxies /api to the Spring Boot app, so the browser sees one origin.
export const api = axios.create({
  headers: { 'Content-Type': 'application/json' },
})

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

// Auth endpoints answer 401 for "wrong password" etc.; that must not trigger a refresh.
function isAuthEndpoint(url: string | undefined): boolean {
  return url?.startsWith('/api/auth/') ?? false
}

// 1. Attach the access token to every request
api.interceptors.request.use((config) => {
  const token = tokenStorage.getAccessToken()
  if (token && !isAuthEndpoint(config.url)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 2. On 401: refresh once, then replay the original request
api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error) || !error.config) {
      throw error
    }
    const original = error.config as RetriableConfig
    const expired = error.response?.status === 401

    if (!expired || original._retried || isAuthEndpoint(original.url)) {
      throw error
    }

    original._retried = true
    try {
      const session = await refreshSession()
      original.headers.Authorization = `Bearer ${session.accessToken}`
    } catch {
      throw error // refresh failed: surface the original 401 (the session listener logs out)
    }
    return api(original)
  },
)
