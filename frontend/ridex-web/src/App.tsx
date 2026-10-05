import { useEffect, useState } from 'react'

type HealthResponse = {
  status: string
  app: string
}

function App() {
  const [health, setHealth] = useState<HealthResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    async function loadHealth() {
      try {
        const response = await fetch('/api/health')
        if (!response.ok) {
          throw new Error(`Server responded with status ${response.status}`)
        }
        const data: HealthResponse = await response.json()
        setHealth(data)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Unknown error')
      }
    }

    loadHealth()
  }, [])

  return (
    <main>
      <h1>RideX</h1>
      {error && <p>Backend error: {error}</p>}
      {!error && !health && <p>Checking backend...</p>}
      {health && (
        <p>
          Backend is {health.status} ({health.app})
        </p>
      )}
    </main>
  )
}

export default App