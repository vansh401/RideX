import type { DocumentType } from '@/api/types'

export const DOCUMENT_LABEL: Record<DocumentType, string> = {
  DRIVING_LICENSE: 'Driving licence',
  VEHICLE_REGISTRATION: 'Vehicle registration (RC)',
  INSURANCE: 'Insurance',
  ID_PROOF: 'ID proof',
}

/** Saves a downloaded blob. The server sends files as attachments, so they are never shown inside our site. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
