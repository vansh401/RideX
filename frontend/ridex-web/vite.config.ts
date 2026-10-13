import path from 'node:path'
import { defineConfig } from 'vite'
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
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
