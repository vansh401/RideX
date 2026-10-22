/**
 * The living background behind every screen: three soft colour pools (gold, blue, violet) that drift
 * slowly. Glass surfaces blur and refract them, which is what makes the interface feel spatial. It is
 * pure CSS (transform animations on their own layers), costs no network request, is hidden from
 * assistive technology, and stops moving when the user prefers reduced motion.
 */
export function AmbientBackground() {
  return (
    <div aria-hidden className="aurora">
      <span />
      <span />
      <span />
    </div>
  )
}
