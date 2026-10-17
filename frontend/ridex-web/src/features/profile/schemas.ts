import { z } from 'zod'

// These mirror the backend rules (UpdateRiderProfileRequest, SavedPlaceRequest,
// DriverProfileRequest). They give instant feedback; the server validates again.

const PHONE = /^\+?[0-9]{10,15}$/

/** Both fields, or both empty to clear the contact. */
export const emergencyContactSchema = z
  .object({
    name: z.string().trim().max(100, 'Use at most 100 characters'),
    phone: z.string().trim(),
  })
  .superRefine((values, ctx) => {
    const hasName = values.name !== ''
    const hasPhone = values.phone !== ''
    if (hasName && !hasPhone) {
      ctx.addIssue({ code: 'custom', path: ['phone'], message: 'Add a phone number for this contact' })
    }
    if (hasPhone && !hasName) {
      ctx.addIssue({ code: 'custom', path: ['name'], message: 'Add a name for this number' })
    }
    if (hasPhone && !PHONE.test(values.phone)) {
      ctx.addIssue({ code: 'custom', path: ['phone'], message: 'Use 10 to 15 digits, with an optional leading +' })
    }
  })

// Coordinates are kept as text in the form and converted on submit: it avoids number-input
// quirks and lets us enforce "at most 6 decimal places" exactly like the database column.
function coordinate(max: number, integerDigits: number, name: string) {
  const shape = new RegExp(`^-?\\d{1,${integerDigits}}(\\.\\d{1,6})?$`)
  return z
    .string()
    .trim()
    .min(1, `Enter the ${name}`)
    .regex(shape, `Use a number with at most 6 decimal places`)
    .refine((value) => Math.abs(Number(value)) <= max, `Must be between -${max} and ${max}`)
}

export const savedPlaceSchema = z.object({
  label: z.string().trim().min(1, 'Give the place a name').max(30, 'Use at most 30 characters'),
  address: z.string().trim().min(1, 'Enter an address').max(255, 'Use at most 255 characters'),
  latitude: coordinate(90, 2, 'latitude'),
  longitude: coordinate(180, 3, 'longitude'),
})

export function todayIso(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

export const licenceSchema = z.object({
  licenseNumber: z
    .string()
    .trim()
    .regex(/^[A-Za-z0-9][A-Za-z0-9 -]{4,29}$/, 'Use 5 to 30 letters, digits, spaces or hyphens'),
  licenseExpiry: z
    .string()
    .min(1, 'Choose the expiry date')
    .refine((value) => value > todayIso(), 'The licence must expire in the future'),
})

export type EmergencyContactValues = z.infer<typeof emergencyContactSchema>
export type SavedPlaceValues = z.infer<typeof savedPlaceSchema>
export type LicenceValues = z.infer<typeof licenceSchema>
