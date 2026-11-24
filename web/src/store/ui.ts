import { create } from 'zustand'
import type { Track } from '../lib/types'

export interface Toast {
  id: number
  text: string
  action?: { label: string; run: () => void }
}

/** Где открыто меню трека — от этого зависят пункты («убрать из плейлиста», «из очереди»). */
export interface TrackMenuCtx {
  playlistId?: number
  queueIndex?: number
}

export interface TrackMenu {
  track: Track
  x: number
  y: number
  ctx: TrackMenuCtx
}

export type Panel = 'queue' | 'lyrics' | null
export type Dialog =
  | { kind: 'eq' }
  | { kind: 'sleep' }
  | { kind: 'shortcuts' }
  | { kind: 'wave' }
  | { kind: 'import-playlist' }
  | { kind: 'new-playlist'; track?: Track; tracks?: Track[]; name?: string }
  | { kind: 'add-to-playlist'; track: Track }
  | null

interface UiState {
  toasts: Toast[]
  menu: TrackMenu | null
  panel: Panel
  dialog: Dialog
  nowPlaying: boolean
  toast: (text: string, action?: Toast['action']) => void
  dismiss: (id: number) => void
  openMenu: (m: TrackMenu) => void
  closeMenu: () => void
  setPanel: (p: Panel) => void
  togglePanel: (p: Exclude<Panel, null>) => void
  openDialog: (d: Dialog) => void
  closeDialog: () => void
  setNowPlaying: (v: boolean) => void
}

let seq = 1

export const useUi = create<UiState>((set, get) => ({
  toasts: [],
  menu: null,
  panel: (localStorage.getItem('music.panel') as Panel) || null,
  dialog: null,
  nowPlaying: false,
  toast: (text, action) => {
    const id = seq++
    set((s) => ({ toasts: [...s.toasts.slice(-2), { id, text, action }] }))
    window.setTimeout(() => get().dismiss(id), action ? 6000 : 3200)
  },
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  openMenu: (menu) => set({ menu }),
  closeMenu: () => set({ menu: null }),
  setPanel: (panel) => {
    localStorage.setItem('music.panel', panel ?? '')
    set({ panel })
  },
  togglePanel: (p) => get().setPanel(get().panel === p ? null : p),
  openDialog: (dialog) => set({ dialog, menu: null }),
  closeDialog: () => set({ dialog: null }),
  setNowPlaying: (nowPlaying) => set({ nowPlaying }),
}))

export const toast = (text: string, action?: Toast['action']) => useUi.getState().toast(text, action)
