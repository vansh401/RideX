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
  rejectionReason: string | null
  createdAt: string
}

// ---------------------------------------------------------------------------------------------
// Phase 3: vehicles, documents, availability, rides, ratings, admin

export type VehicleCategory = 'BIKE' | 'AUTO' | 'CAB'

export interface LatLng {
  latitude: number
  longitude: number
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Vehicle {
  id: string
  category: VehicleCategory
  make: string
  model: string
  color: string
  registrationNumber: string
  manufactureYear: number
  seats: number
  active: boolean
}

export type DocumentType = 'DRIVING_LICENSE' | 'VEHICLE_REGISTRATION' | 'INSURANCE' | 'ID_PROOF'

export interface DriverDocument {
  id: string
  type: DocumentType
  originalFilename: string
  contentType: string
  sizeBytes: number
  uploadedAt: string
}

export interface Availability {
  online: boolean
  approvalStatus: ApprovalStatus
  hasActiveVehicle: boolean
  lastLatitude: number | null
  lastLongitude: number | null
  lastSeenAt: string | null
}

export interface FareEstimate {
  category: VehicleCategory
  distanceKm: number
  durationMinutes: number
  baseFare: number
  distanceFare: number
  timeFare: number
  minimumFareApplied: boolean
  surgeMultiplier: number
  subtotal: number
  tax: number
  total: number
  currency: string
}

export type RideStatus =
  | 'REQUESTED'
  | 'SEARCHING_DRIVER'
  | 'DRIVER_ASSIGNED'
  | 'DRIVER_ACCEPTED'
  | 'DRIVER_ARRIVED'
  | 'RIDE_STARTED'
  | 'RIDE_COMPLETED'
  | 'CANCELLED'

export type ActorRole = 'RIDER' | 'DRIVER' | 'ADMIN' | 'SYSTEM'

export interface Place {
  address: string
  latitude: number
  longitude: number
}

export interface Ride {
  id: string
  status: RideStatus
  category: VehicleCategory
  pickup: Place
  dropoff: Place
  distanceKm: number
  durationMinutes: number
  fare: {
    baseFare: number
    distanceFare: number
    timeFare: number
    surgeMultiplier: number
    subtotal: number
    tax: number
    total: number
    currency: string
  }
  rider: { userId: string; fullName: string; phone: string | null }
  driver: { userId: string; fullName: string; phone: string | null; ratingAverage: number | null; ratingCount: number } | null
  vehicle: { category: VehicleCategory; make: string; model: string; color: string; registrationNumber: string; seats: number } | null
  requestedAt: string
  offerExpiresAt: string | null
  acceptedAt: string | null
  arrivedAt: string | null
  startedAt: string | null
  completedAt: string | null
  cancelledAt: string | null
  cancelledBy: ActorRole | null
  cancelReason: string | null
  myRating: number | null
}

export interface TimelineEntry {
  from: RideStatus | null
  to: RideStatus
  actorRole: ActorRole
  note: string | null
  at: string
}

export interface AdminDriverSummary {
  id: string
  userId: string
  fullName: string
  email: string
  phone: string
  licenseNumber: string
  licenseExpiry: string
  approvalStatus: ApprovalStatus
  createdAt: string
}

export interface AdminDriverDetail extends AdminDriverSummary {
  rejectionReason: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  documents: DriverDocument[]
  vehicles: Vehicle[]
}

export interface AdminUser {
  id: string
  fullName: string
  email: string
  phone: string
  role: Role
  status: 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED'
  createdAt: string
}

export interface AdminVehicle {
  id: string
  driverProfileId: string
  driverName: string
  driverEmail: string
  driverStatus: ApprovalStatus
  category: VehicleCategory
  make: string
  model: string
  color: string
  registrationNumber: string
  active: boolean
}

export interface AdminStats {
  usersByRole: Record<Role, number>
  suspendedUsers: number
  pendingDriverApplications: number
  onlineDrivers: number
  ridesByStatus: Record<RideStatus, number>
  ridesInLast24Hours: number
  completedRevenue: number
  currency: string
}

// ---------------------------------------------------------------------------------------------
// Phase 5: real time and notifications

export type NotificationType =
  | 'RIDE_OFFER'
  | 'RIDE_ACCEPTED'
  | 'DRIVER_ARRIVED'
  | 'TRIP_STARTED'
  | 'TRIP_COMPLETED'
  | 'RIDE_CANCELLED'
  | 'NO_DRIVER_FOUND'
  | 'DRIVER_APPROVED'
  | 'DRIVER_REJECTED'

export interface AppNotification {
  id: string
  type: NotificationType
  title: string
  body: string
  rideId: string | null
  createdAt: string
  read: boolean
}

/** Pushed on /user/queue/ride-updates: only a hint, the details come from GET /api/rides/{id}. */
export interface LiveRideUpdate {
  rideId: string
  status: RideStatus
  previousStatus: RideStatus
  at: string
}

/** Pushed on /user/queue/offers. */
export interface LiveOffer {
  rideId: string
  withdrawn: boolean
  category?: VehicleCategory
  pickupAddress?: string
  dropoffAddress?: string
  distanceKm?: number
  totalFare?: number
  expiresAt?: string
}

/** Pushed on /topic/rides/{id}/location, and returned by GET /api/rides/{id}/driver-location. */
export interface DriverPosition {
  latitude: number
  longitude: number
  at?: string
  updatedAt?: string | null
}

// ---------------------------------------------------------------------------------------------
// Phase 6: events and payments

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED'

export interface Payment {
  id: string
  rideId: string
  amount: number
  currency: string
  status: PaymentStatus
  attempts: number
  failureReason: string | null
  gatewayReference: string | null
  createdAt: string
  updatedAt: string
}

export interface ConsumerLag {
  group: string
  topic: string
  lag: number
}

export interface DeadLetter {
  id: number
  sourceTopic: string
  partition: number | null
  offset: number | null
  key: string | null
  failedGroup: string | null
  error: string | null
  payload: string | null
  createdAt: string
}

export interface AuditEntry {
  id: number
  eventId: string
  topic: string
  partition: number
  offset: number
  key: string
  type: string
  payload: string
  occurredAt: string
  receivedAt: string
}

export interface DailyCount {
  day: string
  status: string
  count: number
}
