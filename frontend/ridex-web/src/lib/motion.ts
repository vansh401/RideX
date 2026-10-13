import type { Transition, Variants } from 'motion/react'

// Motion tokens in JS form. They mirror --dur-* / --ease-* in styles/globals.css.
// Rule: animate transform and opacity only.

export const duration = { fast: 0.12, base: 0.2, slow: 0.32, page: 0.48 } as const

export const ease = {
  standard: [0.2, 0, 0, 1],
  emphasized: [0.16, 1, 0.3, 1],
  exit: [0.4, 0, 1, 1],
} as const

export const transition = {
  base: { duration: duration.base, ease: ease.standard } satisfies Transition,
  slow: { duration: duration.slow, ease: ease.emphasized } satisfies Transition,
  page: { duration: duration.page, ease: ease.emphasized } satisfies Transition,
}

/** Content that rises a little while fading in (sections, cards, page changes). */
export const reveal: Variants = {
  hidden: { opacity: 0, y: 12 },
  visible: { opacity: 1, y: 0, transition: transition.slow },
}

/** Container that reveals its children one after another. */
export const stagger: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.08 } },
}
