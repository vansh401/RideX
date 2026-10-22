import { motion } from 'motion/react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { transition } from '@/lib/motion'

/** Opens the new page like a window: it fades in, rises, settles from a slight scale and comes into focus.
 * (The blur lasts under half a second and only runs on a route change.) */
export function PageTransition({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return (
    <motion.div
      key={pathname}
      initial={{ opacity: 0, y: 16, scale: 0.985, filter: 'blur(8px)' }}
      animate={{ opacity: 1, y: 0, scale: 1, filter: 'blur(0px)' }}
      transition={transition.page}
    >
      {children}
    </motion.div>
  )
}
