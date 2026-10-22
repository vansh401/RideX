import type { PointerEvent } from 'react'

/**
 * Pointer-follow light for glass surfaces. Moving a mouse over the element sets four CSS variables
 * (--mx / --my: where the highlight sits, --rx / --ry: a small 3D tilt). No React state is touched,
 * so nothing re-renders while the pointer moves. Touch and pen input are ignored (no hover on
 * phones), and the CSS only reads the variables inside hover rules and not under reduced motion.
 */
export function useSpotlight(maxTiltDegrees = 4) {
  return {
    onPointerMove(event: PointerEvent<HTMLElement>) {
      if (event.pointerType !== 'mouse') return
      const el = event.currentTarget
      const rect = el.getBoundingClientRect()
      const x = (event.clientX - rect.left) / rect.width
      const y = (event.clientY - rect.top) / rect.height
      el.style.setProperty('--mx', `${(x * 100).toFixed(1)}%`)
      el.style.setProperty('--my', `${(y * 100).toFixed(1)}%`)
      el.style.setProperty('--ry', `${((x - 0.5) * maxTiltDegrees * 2).toFixed(2)}deg`)
      el.style.setProperty('--rx', `${((0.5 - y) * maxTiltDegrees * 2).toFixed(2)}deg`)
    },
    onPointerLeave(event: PointerEvent<HTMLElement>) {
      const el = event.currentTarget
      for (const name of ['--mx', '--my', '--rx', '--ry']) el.style.removeProperty(name)
    },
  }
}
