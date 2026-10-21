import path from 'node:path'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // "@/components/ui/button" instead of "../../../components/ui/button"
    alias: { '@': path.resolve(import.meta.dirname, './src') },
  },
  // These are only imported by lazily loaded pages. Declaring them up front stops the dev
  // server re-bundling mid-session (which shows up as a blank page and "Outdated Optimize Dep").
  optimizeDeps: {
    include: ['react-hook-form', 'zod', '@hookform/resolvers/zod', 'sonner', 'radix-ui', 'lucide-react'],
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
  server: {
    // Phase 8: the browser talks to ONE address, the API gateway (port 8000). It checks the token, rate-limits, and routes each path to
    // the service that owns it (see docs/gateway-concepts.md). Nothing here knows about the individual services any more: moving a
    // path from one service to another is a change in the gateway, not in the frontend.
    // Because the browser only ever sees localhost:5173, requests are same-origin: no CORS is involved in development.
    proxy: {
      '/api': { target: 'http://localhost:8000', changeOrigin: true },
      // The live-updates WebSocket (STOMP) goes through the gateway too
      '/ws': { target: 'http://localhost:8000', ws: true, changeOrigin: true },
    },
  },
})
