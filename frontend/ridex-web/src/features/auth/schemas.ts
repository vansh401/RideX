import { z } from 'zod'

// These mirror the backend's Bean Validation rules (RegisterRequest, ResetPasswordRequest).
// They only give instant feedback; the server validates again and is the authority.

const password = z
  .string()
  .min(8, 'Use at least 8 characters')
  .max(72, 'Use at most 72 characters')
  .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, 'Include at least one letter and one digit')

export const loginSchema = z.object({
  email: z.email('Enter a valid email address'),
  password: z.string().min(1, 'Enter your password'),
})

export const registerSchema = z.object({
  fullName: z.string().trim().min(1, 'Enter your full name').max(100, 'Use at most 100 characters'),
  email: z.email('Enter a valid email address').max(255, 'Use at most 255 characters'),
  phone: z.string().regex(/^\+?[0-9]{10,15}$/, 'Use 10 to 15 digits, with an optional leading +'),
  password,
})

export const forgotSchema = z.object({
  email: z.email('Enter a valid email address'),
})

export const resetSchema = z
  .object({
    password,
    confirm: z.string().min(1, 'Repeat your new password'),
  })
  .refine((values) => values.password === values.confirm, {
    message: 'The passwords do not match',
    path: ['confirm'],
  })

// The 6-digit code from the verification email (backend: VerifyOtpRequest)
export const otpSchema = z.object({
  email: z.email('Enter a valid email address'),
  code: z.string().regex(/^[0-9]{6}$/, 'Enter the 6 digits from the email'),
})

export type OtpValues = z.infer<typeof otpSchema>
export type LoginValues = z.infer<typeof loginSchema>
export type RegisterValues = z.infer<typeof registerSchema>
export type ForgotValues = z.infer<typeof forgotSchema>
export type ResetValues = z.infer<typeof resetSchema>
