import { z } from 'zod'

export const vehicleSchema = z.object({
  category: z.enum(['BIKE', 'AUTO', 'CAB']),
  make: z.string().trim().min(1, 'Required').max(50),
  model: z.string().trim().min(1, 'Required').max(50),
  color: z.string().trim().min(1, 'Required').max(30),
  registrationNumber: z
    .string()
    .trim()
    .regex(/^[A-Za-z]{2}[ -]?\d{1,2}[ -]?[A-Za-z]{0,3}[ -]?\d{4}$/, 'Use a format like KA01AB1234'),
  manufactureYear: z.coerce.number().int().min(2000, 'Must be 2000 or later').max(2100),
  seats: z.coerce.number().int().min(1).max(6),
})
export type VehicleInput = z.input<typeof vehicleSchema>
export type VehicleValues = z.output<typeof vehicleSchema>
