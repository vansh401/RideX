/**
 * Night-city scene for the hero and the auth screens. Pure SVG, no image asset and no network request:
 * a dark city grid, soft ambient light, a glowing gold route from pickup to destination whose dashes
 * travel along it, and a sleek car with lit headlights in the foreground. Decorative, so hidden from
 * assistive tech. The route animation is a CSS animation, which the reduced-motion rule switches off.
 */
export function HeroArt({ className }: { className?: string }) {
  return (
    <svg aria-hidden viewBox="0 0 900 760" preserveAspectRatio="xMidYMid slice" className={className} fill="none">
      <defs>
        <radialGradient id="glow-a" cx="72%" cy="34%" r="48%">
          <stop offset="0%" stopColor="#e9c84a" stopOpacity="0.2" />
          <stop offset="100%" stopColor="#e9c84a" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="glow-b" cx="18%" cy="88%" r="44%">
          <stop offset="0%" stopColor="#72aeff" stopOpacity="0.12" />
          <stop offset="100%" stopColor="#72aeff" stopOpacity="0" />
        </radialGradient>
        <linearGradient id="body" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#2b2f36" />
          <stop offset="55%" stopColor="#14171b" />
          <stop offset="100%" stopColor="#0a0b0d" />
        </linearGradient>
        <linearGradient id="beam" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="#fff6cf" stopOpacity="0.55" />
          <stop offset="100%" stopColor="#fff6cf" stopOpacity="0" />
        </linearGradient>
        <filter id="soft" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="6" />
        </filter>
      </defs>

      <rect width="900" height="760" fill="#07080a" />
      <rect width="900" height="760" fill="url(#glow-a)" />
      <rect width="900" height="760" fill="url(#glow-b)" />

      {/* City blocks */}
      <g fill="#0d0f12" stroke="#181b20">
        <rect x="40" y="70" width="170" height="120" rx="16" />
        <rect x="250" y="40" width="130" height="150" rx="16" />
        <rect x="560" y="60" width="150" height="110" rx="16" />
        <rect x="740" y="90" width="130" height="170" rx="16" />
        <rect x="60" y="400" width="190" height="110" rx="16" />
        <rect x="620" y="440" width="190" height="110" rx="16" />
      </g>

      {/* Roads */}
      <g stroke="#14171b" strokeWidth="46" strokeLinecap="round">
        <path d="M-20 300 C 160 250, 300 360, 470 320 S 760 230, 940 290" />
        <path d="M120 -20 C 160 160, 90 330, 190 520 S 260 700, 240 790" />
        <path d="M520 -20 C 480 140, 600 250, 560 420 S 640 640, 700 790" />
      </g>
      <g stroke="#23272d" strokeWidth="2" strokeDasharray="10 14" strokeLinecap="round">
        <path d="M-20 300 C 160 250, 300 360, 470 320 S 760 230, 940 290" />
      </g>

      {/* Route: a soft halo underneath, then dashes that travel toward the destination */}
      <path
        d="M170 470 C 230 380, 300 420, 380 340 S 540 290, 640 220 S 760 140, 790 120"
        stroke="#e9c84a"
        strokeOpacity="0.35"
        strokeWidth="10"
        strokeLinecap="round"
        filter="url(#soft)"
      />
      <path
        d="M170 470 C 230 380, 300 420, 380 340 S 540 290, 640 220 S 760 140, 790 120"
        stroke="#e9c84a"
        strokeWidth="4"
        strokeLinecap="round"
        strokeDasharray="2 12"
        style={{ animation: 'route-flow 1.4s linear infinite' }}
      />

      {/* Pickup */}
      <g transform="translate(170 470)">
        <circle r="26" fill="#f6f6f3" opacity="0.08" />
        <circle r="15" fill="#f6f6f3" />
        <circle r="6" fill="#060709" />
      </g>
      {/* Destination */}
      <g transform="translate(790 120)">
        <circle cy="4" r="30" fill="#e9c84a" opacity="0.12" />
        <path
          d="M0 -34 C -17 -34 -26 -21 -26 -9 C -26 11 0 30 0 30 C 0 30 26 11 26 -9 C 26 -21 17 -34 0 -34 Z"
          fill="#e9c84a"
        />
        <circle cy="-10" r="8" fill="#17140a" />
      </g>

      {/* The car: side view, headlights facing right, light spilling onto the road */}
      <g transform="translate(300 500)">
        <ellipse cx="190" cy="96" rx="210" ry="16" fill="#000" opacity="0.55" filter="url(#soft)" />
        <path d="M330 74 L640 62 L640 92 L330 98 Z" fill="url(#beam)" opacity="0.55" />
        <path
          d="M20 84 C 20 66, 38 58, 70 54 L116 28 C 128 20, 142 16, 160 16 L250 16 C 272 16, 290 24, 306 40 L338 56 C 366 60, 380 70, 380 84 L380 96 L20 96 Z"
          fill="url(#body)"
          stroke="#2d3239"
          strokeWidth="1.5"
        />
        <path d="M128 52 L152 28 L206 28 L206 52 Z" fill="#0b0d10" stroke="#262a30" />
        <path d="M216 52 L216 28 L252 28 C 266 30, 276 38, 290 52 Z" fill="#0b0d10" stroke="#262a30" />
        <path d="M30 70 C 120 64, 260 64, 372 72" stroke="#e9c84a" strokeOpacity="0.55" strokeWidth="1.5" />
        <circle cx="94" cy="96" r="26" fill="#07080a" stroke="#2d3239" strokeWidth="2" />
        <circle cx="94" cy="96" r="11" fill="#1a1d22" />
        <circle cx="304" cy="96" r="26" fill="#07080a" stroke="#2d3239" strokeWidth="2" />
        <circle cx="304" cy="96" r="11" fill="#1a1d22" />
        <rect x="352" y="62" width="26" height="9" rx="4.5" fill="#fff6cf" />
        <rect x="352" y="62" width="26" height="9" rx="4.5" fill="#fff6cf" filter="url(#soft)" opacity="0.9" />
        <rect x="21" y="62" width="14" height="7" rx="3.5" fill="#ff6b6b" opacity="0.9" />
      </g>
    </svg>
  )
}
