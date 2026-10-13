import axios from 'axios'
import { tokenStorage } from '../auth/tokenStorage'
import type { LoginResponse, UserSummary } from './types'

// Session handling that does not depend on the main Axios instance (so there is no import
// cycle and no interceptor can recurse into a refresh).

type Listener = (user: UserSummary | null) => void
const listeners = new Set<Listener>()

/** Subscribe to "who is logged in" changes. Returns an unsubscribe function. */
export function onSessionChange(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function emit(user: UserSummary | null): void {
  listeners.forEach((listener) => listener(user))
}

/** Called after a successful login or refresh. */
export function startSession(response: LoginResponse): void {
  // Store the NEW refresh token immediately: the old one is already dead (rotation)
  tokenStorage.setTokens(response.accessToken, response.refreshToken)
  emit(response.user)
}

export function endSession(): void {
  tokenStorage.clear()
  emit(null)
}

// Single flight: if five requests get a 401 at once (or React StrictMode runs an effect
// twice), they all wait for ONE refresh call. Two refreshes with the same token would look
// like token theft to the server and revoke the whole session.
let inFlight: Promise<LoginResponse> | null = null

export function refreshSession(): Promise<LoginResponse> {
  if (!inFlight) {
    inFlight = doRefresh().finally(() => {
      inFlight = null
    })
  }
  return inFlight
}

async function doRefresh(): Promise<LoginResponse> {
  const refreshToken = tokenStorage.getRefreshToken()
  if (!refreshToken) {
    endSession()
    throw new Error('No refresh token')
  }
  try {
    const { data } = await axios.post<LoginResponse>('/api/auth/refresh', { refreshToken })
    startSession(data)
    return data
  } catch (error) {
    // The server answered "no": the session is really over.
    // No answer at all (server down, offline): keep the token and let the user retry.
    if (axios.isAxiosError(error) && error.response) {
      endSession()
    }
    throw error
  }
}
