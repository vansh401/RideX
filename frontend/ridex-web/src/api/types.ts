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
  emergencyContact: { name: string; phone: string; email?: string | null } | null
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
  | 'SCHEDULED'
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
  scheduledFor?: string | null
  couponCode?: string | null
  stops?: RideStop[]
  stopsDone?: number
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
  discount?: number
  method?: string | null
  couponCode?: string | null
  refundedAmount?: number
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

/** The state of the protections around the payment gateway (circuit breaker, bulkhead) and of the test gateway itself. */
export interface GatewayStatus {
  circuitState: 'CLOSED' | 'OPEN' | 'HALF_OPEN' | 'DISABLED' | 'FORCED_OPEN' | 'METRICS_ONLY'
  failureRatePercent: number | null
  bufferedCalls: number
  failedCalls: number
  callsRefusedByCircuit: number
  bulkheadAvailableCalls: number
  bulkheadMaxCalls: number
  chaos: { mode: 'NORMAL' | 'ERROR' | 'SLOW' | 'SLOW_AFTER_PROCESSING'; delayMs: number }
  callsReceivedByGateway: number
}

// ---- Phase 14 ----

export interface RideStop {
  position: number
  address: string
  latitude: number
  longitude: number
  arrivedAt: string | null
}

export interface Wallet {
  balance: number
  currency: string
}

export interface WalletEntry {
  id: number
  type: string
  amount: number
  balanceAfter: number
  description: string
  reference: string | null
  at: string
}

export interface CouponPreview {
  eligible: boolean
  reason: 'OK' | 'UNKNOWN' | 'INACTIVE' | 'NOT_STARTED' | 'EXPIRED' | 'FARE_TOO_LOW' | 'SOLD_OUT' | 'ALREADY_USED'
  discount: number
  code: string
}

export interface Coupon {
  id: string
  code: string
  description: string | null
  discountType: 'PERCENT' | 'FLAT'
  value: number
  maxDiscount: number | null
  minFare: number | null
  validFrom: string
  validUntil: string
  maxTotalUses: number | null
  perUserLimit: number
  usedCount: number
  active: boolean
}

export interface EarningsSummary {
  currency: string
  balance: number
  today: number
  tripsToday: number
  last7Days: number
  trips7Days: number
  last30Days: number
  trips30Days: number
  bonuses30Days: number
  daily: { day: string; amount: number; trips: number }[]
  recent: { rideId: string; amount: number; at: string }[]
  generatedAt: string
}

export type ComplaintCategory =
  | 'OVERCHARGED'
  | 'RUDE_BEHAVIOUR'
  | 'UNSAFE_DRIVING'
  | 'ROUTE_ISSUE'
  | 'VEHICLE_ISSUE'
  | 'PAYMENT_ISSUE'
  | 'LOST_ITEM'
  | 'OTHER'

export type ComplaintStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'REJECTED'

export interface Complaint {
  id: string
  rideId: string
  category: ComplaintCategory
  description: string
  status: ComplaintStatus
  filedByRole: string
  filedByName: string | null
  assignedTo: string | null
  resolutionNote: string | null
  refundAmount: number | null
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  comments: { id: string; authorRole: string; message: string; internal: boolean; createdAt: string }[]
  ride: { pickup: string; dropoff: string; fare: number; status: string } | null
}

export interface AnalyticsOverview {
  days: number
  ridesRequested: number
  ridesCompleted: number
  ridesCancelled: number
  cancellationRate: number
  grossBookings: number
  averageFare: number
  averageSecondsToAccept: number | null
  ridesWithSurge: number
  collected: number
  couponDiscounts: number
  refunds: number
  paymentsSucceeded: number
  paymentsFailed: number
  complaints: number
}

export interface FraudSignal {
  id: number
  rule: string
  subjectType: string
  subjectId: string
  severity: number
  details: string
  rideId: string | null
  status: 'NEW' | 'CONFIRMED' | 'DISMISSED'
  createdAt: string
  reviewedAt: string | null
}

// ---------------------------------------------------------------------------------------------
// Phase 15: online payments, tips, receipts, referrals, payouts, safety, chat

export interface OnlineConfig {
  provider: 'STRIPE' | 'RAZORPAY' | 'MOCK'
  publicKey: string
}

export interface OnlineOrder {
  id: string
  provider: 'STRIPE' | 'RAZORPAY' | 'MOCK'
  publicKey: string
  orderId: string
  /** Set for a hosted checkout (Stripe): the page the rider is sent to. */
  checkoutUrl?: string | null
  amount: number
  currency: string
  purpose: 'TOPUP' | 'RIDE'
  rideId: string | null
}

export interface Receipt {
  number: string
  rideId: string
  issuedAt: string
  currency: string
  fare: number
  discount: number
  couponCode: string | null
  paid: number
  tip: number | null
  refunded: number
  method: string | null
  status: string
}

export interface Offer {
  code: string
  description: string | null
  discountType: 'PERCENT' | 'FLAT'
  value: number
  maxDiscount: number | null
  minFare: number | null
  validUntil: string
  usesLeft: number
}

export interface ReferralSummary {
  code: string
  reward: number
  invited: number
  rewarded: number
  appliedCode: string | null
  rewardPending: boolean
}

export interface IncentiveProgress {
  day: string
  tripsToday: number
  tiers: { trips: number; bonus: number }[]
  next: { trips: number; bonus: number } | null
  tripsToNext: number
  bonusSoFar: number
}

export type PayoutStatus = 'REQUESTED' | 'PAID' | 'REJECTED'

export interface Payout {
  id: string
  driverUserId: string
  amount: number
  upiId: string
  status: PayoutStatus
  requestedAt: string
  decidedAt: string | null
  note: string | null
  reference: string | null
}

export interface ShareLink {
  token: string
  url: string
  expiresAt: string
}

export interface TripView {
  status: RideStatus
  driverFirstName: string | null
  vehicle: { description: string; registrationNumber: string } | null
  pickupAddress: string
  dropoffAddress: string
  pickupLatitude: number
  pickupLongitude: number
  dropoffLatitude: number
  dropoffLongitude: number
  position: { latitude: number; longitude: number; updatedAt: string | null } | null
  expiresAt: string
}

export interface SosAlert {
  id: string
  rideId: string
  raisedByName: string
  raisedByRole: 'RIDER' | 'DRIVER'
  raisedByPhone: string | null
  pickupAddress: string
  dropoffAddress: string
  latitude: number | null
  longitude: number | null
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
  note: string | null
  createdAt: string
  handledAt: string | null
}

export interface ChatMessage {
  id: string
  senderRole: 'RIDER' | 'DRIVER'
  body: string
  createdAt: string
  mine: boolean
}

export interface SurgeZone {
  zone: string
  multiplier: number
  latitude: number
  longitude: number
  radiusKm: number
}

export interface LedgerIntegrity {
  ok: boolean
  sumOfAllBalances: number
  accountsOutOfStep: string[]
}

export interface RefundRecord {
  id: string
  paymentId: string
  rideId: string
  userId: string
  amount: number
  reason: string | null
  source: string
  destination: string
  status: string
  attempts: number
  failureReason: string | null
  createdAt: string
}
