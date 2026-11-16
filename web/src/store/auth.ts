import { create } from 'zustand'
import { api, setUnauthorizedHandler, token } from '../lib/api'
import type { Profile } from '../lib/types'

type Status = 'loading' | 'guest' | 'user'

interface AuthState {
  status: Status
  me: Profile | null
  init: () => Promise<void>
  signedIn: (t: string) => Promise<void>
  refresh: () => Promise<void>
  logout: () => Promise<void>
  dropSession: () => void
}

export const useAuth = create<AuthState>((set, get) => ({
  status: token.get() ? 'loading' : 'guest',
  me: null,
  init: async () => {
    if (!token.get()) return set({ status: 'guest' })
    try {
      const me = await api.me()
      set({ me, status: 'user' })
    } catch (e) {
      // Сеть упала — не выкидываем, просто остаёмся «загрузка» и пробуем позже.
      if ((e as { status?: number }).status === 401) get().dropSession()
      else set({ status: 'user' })
    }
  },
  signedIn: async (t) => {
    token.set(t)
    set({ status: 'loading' })
    await get().init()
  },
  refresh: async () => {
    try {
      set({ me: await api.me() })
    } catch {
      /* noop */
    }
  },
  logout: async () => {
    try {
      await api.logout()
    } catch {
      /* всё равно выходим */
    }
    get().dropSession()
  },
  dropSession: () => {
    token.clear()
    set({ status: 'guest', me: null })
  },
}))

setUnauthorizedHandler(() => {
  if (useAuth.getState().status !== 'guest') useAuth.getState().dropSession()
})
