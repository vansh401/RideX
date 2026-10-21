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
    // Phase 7: the backend is several services. In development this proxy plays the part of an API GATEWAY: the browser only
    // knows one address, and each path prefix is routed to the service that owns it. The most specific prefixes come first;
    // everything else is the core application. (In production a reverse proxy such as nginx does the same: docs/service-map.md.)
    proxy: {
      '/api/notifications': { target: 'http://localhost:8081', changeOrigin: true },
      '/api/payments': { target: 'http://localhost:8082', changeOrigin: true },
      '/api/admin/payments': { target: 'http://localhost:8082', changeOrigin: true },
      '/api/fares': { target: 'http://localhost:8083', changeOrigin: true },
      '/api/admin/events': { target: 'http://localhost:8084', changeOrigin: true },
      '/api/admin/analytics': { target: 'http://localhost:8084', changeOrigin: true },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // The live-updates WebSocket (STOMP) goes to the backend too
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
