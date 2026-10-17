import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Unmount whatever a test rendered, and wipe browser storage between tests
afterEach(() => {
  cleanup()
  localStorage.clear()
})
