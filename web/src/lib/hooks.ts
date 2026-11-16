import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'

export interface Async<T> {
  data: T | null
  error: string | null
  loading: boolean
  reload: () => void
  setData: (d: T | null) => void
}

const memo = new Map<string, { at: number; data: unknown }>()

/** Загрузка с коротким кэшем по ключу: возврат на страницу — без мигания. */
export function useAsync<T>(key: string | null, fn: () => Promise<T>, ttl = 60_000): Async<T> {
  const cached = key ? (memo.get(key) as { at: number; data: T } | undefined) : undefined
  const [data, setData] = useState<T | null>(cached ? cached.data : null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(!!key && !cached)
  const [tick, setTick] = useState(0)
  const fnRef = useRef(fn)
  fnRef.current = fn

  useEffect(() => {
    if (!key) {
      setData(null)
      setLoading(false)
      return
    }
    let alive = true
    const c = memo.get(key) as { at: number; data: T } | undefined
    if (c) setData(c.data)
    else setData(null)
    if (c && Date.now() - c.at < ttl && tick === 0) {
      setLoading(false)
      return
    }
    setLoading(!c)
    setError(null)
    fnRef
      .current()
      .then((d) => {
        if (!alive) return
        memo.set(key, { at: Date.now(), data: d })
        setData(d)
      })
      .catch((e) => alive && setError((e as Error).message))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [key, tick, ttl])

  const reload = useCallback(() => {
    if (key) memo.delete(key)
    setTick((t) => t + 1)
  }, [key])
  const set = useCallback(
    (d: T | null) => {
      if (key && d) memo.set(key, { at: Date.now(), data: d })
      setData(d)
    },
    [key],
  )
  return { data, error, loading, reload, setData: set }
}

export const invalidate = (prefix: string) => {
  for (const k of memo.keys()) if (k.startsWith(prefix)) memo.delete(k)
}

export function useMedia(q: string): boolean {
  return useSyncExternalStore(
    (cb) => {
      const m = window.matchMedia(q)
      m.addEventListener('change', cb)
      return () => m.removeEventListener('change', cb)
    },
    () => window.matchMedia(q).matches,
  )
}

export const useMobile = () => useMedia('(max-width: 760px)')

export function useDebounced<T>(v: T, ms: number): T {
  const [d, setD] = useState(v)
  useEffect(() => {
    const t = window.setTimeout(() => setD(v), ms)
    return () => window.clearTimeout(t)
  }, [v, ms])
  return d
}

/** Долгое нажатие на телефоне — вместо правого клика. */
export function useLongPress(fn: (x: number, y: number) => void, ms = 480) {
  const t = useRef(0)
  const fired = useRef(false)
  const start = (e: React.PointerEvent) => {
    if (e.pointerType === 'mouse') return
    fired.current = false
    const { clientX, clientY } = e
    t.current = window.setTimeout(() => {
      fired.current = true
      navigator.vibrate?.(10)
      fn(clientX, clientY)
    }, ms)
  }
  const cancel = () => window.clearTimeout(t.current)
  return {
    onPointerDown: start,
    onPointerUp: cancel,
    onPointerLeave: cancel,
    onPointerMove: (e: React.PointerEvent) => {
      if (Math.abs(e.movementX) + Math.abs(e.movementY) > 6) cancel()
    },
    wasLongPress: () => fired.current,
  }
}
