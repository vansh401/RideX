import '@fontsource-variable/dm-sans'
import { MotionConfig } from 'motion/react'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AmbientBackground } from './components/brand/AmbientBackground.tsx'
import { RouteProgress } from './components/brand/RouteProgress.tsx'
import { Toaster } from './components/ui/sonner.tsx'
import './styles/globals.css'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthProvider.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* "user": animations obey the operating system's reduced-motion setting */}
    <MotionConfig reducedMotion="user">
      <BrowserRouter>
        <AuthProvider>
          <AmbientBackground />
          <RouteProgress />
          <App />
          <Toaster />
        </AuthProvider>
      </BrowserRouter>
    </MotionConfig>
  </StrictMode>,
)
