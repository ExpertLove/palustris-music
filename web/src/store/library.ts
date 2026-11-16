import { create } from 'zustand'
import { api } from '../lib/api'
import type { PlaylistRow, Track } from '../lib/types'
import { toast } from './ui'

interface LibraryState {
  likes: Track[]
  liked: Set<string>
  playlists: PlaylistRow[]
  loaded: boolean
  load: () => Promise<void>
  loadPlaylists: () => Promise<void>
  toggleLike: (t: Track) => Promise<void>
  createPlaylist: (name: string, items?: Track[]) => Promise<number | null>
  addToPlaylist: (pid: number, t: Track) => Promise<void>
  bumpPlaylist: (pid: number, delta: number, thumb?: string | null) => void
  reset: () => void
}

export const useLibrary = create<LibraryState>((set, get) => ({
  likes: [],
  liked: new Set(),
  playlists: [],
  loaded: false,
  load: async () => {
    try {
      const [likes, playlists] = await Promise.all([api.likes(), api.playlists()])
      set({ likes, liked: new Set(likes.map((t) => t.id)), playlists, loaded: true })
    } catch {
      set({ loaded: true })
    }
  },
  loadPlaylists: async () => {
    try {
      set({ playlists: await api.playlists() })
    } catch {
      /* noop */
    }
  },
  toggleLike: async (t) => {
    const was = get().liked.has(t.id)
    const liked = new Set(get().liked)
    if (was) {
      liked.delete(t.id)
      set({ liked, likes: get().likes.filter((x) => x.id !== t.id) })
    } else {
      liked.add(t.id)
      set({ liked, likes: [t, ...get().likes] })
    }
    try {
      if (was) await api.unlike(t.id)
      else await api.like(t)
      toast(was ? 'Убрано из «Мне нравится»' : 'Добавлено в «Мне нравится»')
    } catch (e) {
      toast((e as Error).message)
      get().load()
    }
  },
  createPlaylist: async (name, items = []) => {
    try {
      const { id } = await api.createPlaylist(name, items)
      await get().loadPlaylists()
      return id
    } catch (e) {
      toast((e as Error).message)
      return null
    }
  },
  addToPlaylist: async (pid, t) => {
    try {
      await api.addToPlaylist(pid, t)
      const p = get().playlists.find((x) => x.id === pid)
      get().bumpPlaylist(pid, 1, t.thumb)
      toast(`Добавлено в «${p?.name ?? 'плейлист'}»`)
    } catch (e) {
      toast((e as Error).message)
    }
  },
  bumpPlaylist: (pid, delta, thumb) =>
    set({
      playlists: get().playlists.map((p) =>
        p.id === pid
          ? {
              ...p,
              count: Math.max(0, p.count + delta),
              thumbs: thumb && !p.thumbs.includes(thumb) && p.thumbs.length < 4 ? [...p.thumbs, thumb] : p.thumbs,
            }
          : p,
      ),
    }),
  reset: () => set({ likes: [], liked: new Set(), playlists: [], loaded: false }),
}))
