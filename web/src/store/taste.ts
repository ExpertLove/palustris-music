import { useMemo } from 'react'
import { create } from 'zustand'
import { api } from '../lib/api'
import { firstArtist } from '../lib/format'
import { invalidate } from '../lib/hooks'
import type { Track } from '../lib/types'
import { current, setHiddenCheck, usePlayer } from '../player/store'
import { useLibrary } from './library'
import { toast } from './ui'

/** Ключ имени для сравнения на клиенте: регистр, «ё», пунктуация не важны. Сервер сравнивает строже
 *  (транслит + согласные), это только чтобы сразу убрать исполнителя из очереди. */
const key = (s: string) =>
  s
    .toLowerCase()
    .replace(/ё/g, 'е')
    .replace(/[^\p{L}\p{N}]+/gu, '')

/** «A, B & C feat. D» → все исполнители трека. */
const artistsOf = (a: string) => a.split(/,|&| и | x | feat\.? | ft\.? /i).map((p) => key(p)).filter(Boolean)

/** Трек скрыт пользователем: «Не нравится» или скрытый исполнитель. */
export function hiddenBy(disliked: Set<string>, blocked: string[]): (t: Track) => boolean {
  const keys = new Set(blocked.map(key))
  return (t) => disliked.has(t.id) || (keys.size > 0 && artistsOf(t.artist).some((k) => keys.has(k)))
}

interface TasteState {
  disliked: Set<string>
  /** Треки с «Не нравится» — для раздела «Скрытое», свежие первыми. */
  tracks: Track[]
  /** Скрытые исполнители: как их назвал пользователь. */
  blocked: string[]
  loaded: boolean
  load: () => Promise<void>
  isBlocked: (artist: string) => boolean
  /** «Не нравится»; повторное нажатие отменяет. */
  toggleDislike: (t: Track) => Promise<void>
  /** «Не предлагать исполнителя» этого трека. */
  hideArtist: (t: Track) => Promise<void>
  undislike: (id: string, quiet?: boolean) => Promise<void>
  unblock: (name: string, quiet?: boolean) => Promise<void>
  reset: () => void
}

/** Рекомендации на клиенте закэшированы — после скрытия их надо взять у сервера заново. */
const dropRecs = () => {
  invalidate('recs')
  invalidate('mixes')
}

let loading: Promise<void> | null = null

export const useTaste = create<TasteState>((set, get) => ({
  disliked: new Set(),
  tracks: [],
  blocked: [],
  loaded: false,

  // Один запрос на всех: его ждут и оболочка, и раздел «Скрытое».
  load: () =>
    (loading ??= api
      .dislikes()
      .then((d) => set({ tracks: d.tracks, disliked: new Set(d.tracks.map((t) => t.id)), blocked: d.artists.map((a) => a.name), loaded: true }))
      .catch(() => set({ loaded: true }))
      .finally(() => {
        loading = null
      })),

  isBlocked: (artist) => {
    const b = get().blocked
    if (!b.length) return false
    const keys = new Set(b.map(key))
    return artistsOf(artist).some((k) => keys.has(k))
  },

  toggleDislike: async (t) => {
    if (get().disliked.has(t.id)) return get().undislike(t.id)
    set({ disliked: new Set(get().disliked).add(t.id), tracks: [t, ...get().tracks.filter((x) => x.id !== t.id)] })
    // Сервер снимает лайк сам; здесь — чтобы сердечко погасло сразу.
    const lib = useLibrary.getState()
    if (lib.liked.has(t.id)) {
      const liked = new Set(lib.liked)
      liked.delete(t.id)
      useLibrary.setState({ liked, likes: lib.likes.filter((x) => x.id !== t.id) })
    }
    const p = usePlayer.getState()
    p.removeUpcoming((x) => x.id === t.id)
    if (current(p)?.id === t.id) p.skipCurrent()
    try {
      await api.dislike(t)
      dropRecs()
      toast('Больше не предложим этот трек', { label: 'Отменить', run: () => void get().undislike(t.id, true) })
    } catch (e) {
      const d = new Set(get().disliked)
      d.delete(t.id)
      set({ disliked: d, tracks: get().tracks.filter((x) => x.id !== t.id) })
      toast((e as Error).message)
    }
  },

  hideArtist: async (t) => {
    const name = firstArtist(t.artist)
    if (!name) return
    if (!get().isBlocked(name)) set({ blocked: [name, ...get().blocked] })
    const p = usePlayer.getState()
    p.removeUpcoming((x) => get().isBlocked(x.artist))
    const cur = current(p)
    if (cur && get().isBlocked(cur.artist)) p.skipCurrent()
    try {
      await api.blockArtist(name)
      dropRecs()
      toast(`${name} больше не будет в рекомендациях`, { label: 'Отменить', run: () => void get().unblock(name, true) })
    } catch (e) {
      set({ blocked: get().blocked.filter((b) => b !== name) })
      toast((e as Error).message)
    }
  },

  undislike: async (id, quiet) => {
    const d = new Set(get().disliked)
    d.delete(id)
    set({ disliked: d, tracks: get().tracks.filter((x) => x.id !== id) })
    try {
      await api.undislike(id)
      dropRecs()
      if (!quiet) toast('Убрали из «Не нравится»')
    } catch (e) {
      toast((e as Error).message)
      void get().load()
    }
  },

  unblock: async (name, quiet) => {
    set({ blocked: get().blocked.filter((b) => key(b) !== key(name)) })
    try {
      await api.unblockArtist(name)
      dropRecs()
      if (!quiet) toast(`${name} снова может попасть в рекомендации`)
    } catch (e) {
      toast((e as Error).message)
      void get().load()
    }
  },

  reset: () => set({ disliked: new Set(), tracks: [], blocked: [], loaded: false }),
}))

// Волна и радио не добавляют в очередь скрытое, даже если сервер ещё не пересчитал подборку.
setHiddenCheck((t) => {
  const s = useTaste.getState()
  return hiddenBy(s.disliked, s.blocked)(t)
})

/** Список без скрытого; пересчитывается, когда что-то скрыли или вернули. */
export function useVisible(list: Track[] | null | undefined): Track[] {
  const disliked = useTaste((s) => s.disliked)
  const blocked = useTaste((s) => s.blocked)
  return useMemo(() => {
    const hidden = hiddenBy(disliked, blocked)
    return (list ?? []).filter((t) => !hidden(t))
  }, [list, disliked, blocked])
}
