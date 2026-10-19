import { useCallback, useEffect, useState } from 'react'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { PageResponse } from '@/api/types'

/** Loads one page of a paginated endpoint and re-loads when the page, size or filters change. */
export function usePagedList<T>(path: string, params: Record<string, string | number | undefined>, size = 10) {
  const [page, setPage] = useState(0)
  const [data, setData] = useState<PageResponse<T> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  // A stable key for "the filters": when they change, go back to the first page
  const filterKey = JSON.stringify(params)
  const [lastFilterKey, setLastFilterKey] = useState(filterKey)
  if (filterKey !== lastFilterKey) {
    setLastFilterKey(filterKey)
    setPage(0)
  }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const query = new URLSearchParams()
      for (const [key, value] of Object.entries(JSON.parse(filterKey) as Record<string, string | number | undefined>)) {
        if (value !== undefined && value !== '') query.set(key, String(value))
      }
      query.set('page', String(page))
      query.set('size', String(size))
      const response = await api.get<PageResponse<T>>(`${path}?${query.toString()}`)
      setData(response.data)
      setError(null)
    } catch (err) {
      setError(toFriendlyError(err).message)
    } finally {
      setLoading(false)
    }
  }, [path, filterKey, page, size])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- load() starts a request and shows its spinner
    void load()
  }, [load])

  return { data, error, loading, page, setPage, reload: load }
}
