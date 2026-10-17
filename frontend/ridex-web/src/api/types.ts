// Shapes of what the backend sends. Keep in sync with the Java records
// (see /swagger-ui.html for the generated contract).

export type Role = 'RIDER' | 'DRIVER' | 'ADMIN'

export interface UserSummary {
  id: string
  fullName: string
  email: string
  role: Role
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
  user: UserSummary
}

export interface UserProfile {
  id: string
  fullName: string
  email: string
  phone: string
  role: Role
  status: 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED'
  createdAt: string
}

export interface ApiErrorBody {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
  fieldErrors: Record<string, string>
}

export interface SavedPlace {
  id: string
  label: string
  address: string
  latitude: number
  longitude: number
}

export interface RiderProfile {
  id: string
  emergencyContact: { name: string; phone: string } | null
  savedPlaces: SavedPlace[]
}

export type ApprovalStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'

export interface DriverProfile {
  id: string
  licenseNumber: string
  licenseExpiry: string
  approvalStatus: ApprovalStatus
  createdAt: string
}
