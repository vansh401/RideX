/**
 * Night-city map illustration for the hero. Pure SVG, no image asset: roads, a dotted gold
 * route from pickup to destination, and a few nearby car markers (the language of the rider
 * map in the references). Decorative, so hidden from assistive tech.
 */
export function HeroArt({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden
      viewBox="0 0 900 760"
      preserveAspectRatio="xMidYMid slice"
      className={className}
      fill="none"
    >
      <defs>
        <radialGradient id="glow-a" cx="72%" cy="38%" r="46%">
          <stop offset="0%" stopColor="#e8c547" stopOpacity="0.14" />
          <stop offset="100%" stopColor="#e8c547" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="glow-b" cx="20%" cy="85%" r="40%">
          <stop offset="0%" stopColor="#6aa8ff" stopOpacity="0.07" />
          <stop offset="100%" stopColor="#6aa8ff" stopOpacity="0" />
        </radialGradient>
      </defs>

      <rect width="900" height="760" fill="#0a0a0c" />
      <rect width="900" height="760" fill="url(#glow-a)" />
      <rect width="900" height="760" fill="url(#glow-b)" />

      {/* City blocks */}
      <g fill="#111114" stroke="#1b1b20">
        <rect x="40" y="70" width="170" height="120" rx="14" />
        <rect x="250" y="40" width="130" height="150" rx="14" />
        <rect x="560" y="60" width="150" height="110" rx="14" />
        <rect x="740" y="90" width="130" height="170" rx="14" />
        <rect x="60" y="430" width="190" height="130" rx="14" />
        <rect x="300" y="520" width="150" height="170" rx="14" />
        <rect x="600" y="500" width="170" height="150" rx="14" />
      </g>

      {/* Roads */}
      <g stroke="#1d1d22" strokeWidth="46" strokeLinecap="round">
        <path d="M-20 300 C 160 250, 300 360, 470 320 S 760 230, 940 290" />
        <path d="M120 -20 C 160 160, 90 330, 190 520 S 260 700, 240 790" />
        <path d="M520 -20 C 480 140, 600 250, 560 420 S 640 640, 700 790" />
        <path d="M-20 600 C 200 560, 420 640, 640 580 S 820 560, 940 600" />
      </g>
      <g stroke="#26262c" strokeWidth="2" strokeDasharray="10 14" strokeLinecap="round">
        <path d="M-20 300 C 160 250, 300 360, 470 320 S 760 230, 940 290" />
        <path d="M-20 600 C 200 560, 420 640, 640 580 S 820 560, 940 600" />
      </g>

      {/* Route */}
      <path
        d="M170 520 C 230 420, 300 470, 380 380 S 540 330, 640 250 S 760 150, 790 130"
        stroke="#e8c547"
        strokeWidth="4"
        strokeLinecap="round"
        strokeDasharray="2 12"
      />

      {/* Pickup */}
      <g transform="translate(170 520)">
        <circle r="16" fill="#f5f5f2" />
        <circle r="6" fill="#08080a" />
      </g>
      {/* Destination */}
      <g transform="translate(790 130)">
        <path
          d="M0 -34 C -17 -34 -26 -21 -26 -9 C -26 11 0 30 0 30 C 0 30 26 11 26 -9 C 26 -21 17 -34 0 -34 Z"
          fill="#e8c547"
        />
        <circle cy="-10" r="8" fill="#17140a" />
      </g>

      {/* Nearby cars */}
      {[
        [380, 380, -10],
        [640, 250, 16],
        [520, 545, 8],
        [260, 300, -20],
        [700, 430, 24],
      ].map(([x, y, rotate]) => (
        <g key={`${x}-${y}`} transform={`translate(${x} ${y}) rotate(${rotate})`}>
          <rect x="-15" y="-15" width="30" height="30" rx="9" fill="#f5f5f2" />
          <rect x="-7" y="-9" width="14" height="18" rx="4" fill="#17140a" />
          <rect x="-5" y="-6" width="10" height="5" rx="1.5" fill="#f5f5f2" />
        </g>
      ))}
    </svg>
  )
}
