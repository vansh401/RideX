import { motion } from 'motion/react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { transition } from '@/lib/motion'

/** Fades and lifts content in whenever the route changes (transform + opacity only). */
export function PageTransition({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return (
    <motion.div
      key={pathname}
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={transition.base}
    >
      {children}
    </motion.div>
  )
}
