import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * A thin gold line that sweeps across the top whenever the page changes. It is feedback, not a delay:
 * the new page renders immediately and the line finishes on its own in about half a second.
 */
export function RouteProgress() {
  const { pathname } = useLocation()
  const [key, setKey] = useState<string | null>(null)

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a navigation just happened, so show the bar
    setKey(pathname)
    const timer = window.setTimeout(() => setKey(null), 650)
    return () => window.clearTimeout(timer)
  }, [pathname])

  return (
    <AnimatePresence>
      {key && (
        <motion.div
          key={key}
          aria-hidden
          className="route-bar"
          initial={{ scaleX: 0, opacity: 1 }}
          animate={{ scaleX: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
        />
      )}
    </AnimatePresence>
  )
}
