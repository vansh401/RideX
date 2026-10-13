import axios from 'axios'
import type { ApiErrorBody } from './types'

export interface FriendlyError {
  message: string
  fieldErrors: Record<string, string>
  status?: number
}

/** Turns whatever was thrown into something a form can show. */
export function toFriendlyError(err: unknown): FriendlyError {
  if (axios.isAxiosError<ApiErrorBody>(err)) {
    if (err.response?.data?.message) {
      return {
        message: err.response.data.message,
        fieldErrors: err.response.data.fieldErrors ?? {},
        status: err.response.status,
      }
    }
    if (!err.response) {
      return { message: 'Cannot reach the server. Check your connection.', fieldErrors: {} }
    }
    return { message: `Request failed (${err.response.status})`, fieldErrors: {}, status: err.response.status }
  }
  return { message: 'Something went wrong', fieldErrors: {} }
}
