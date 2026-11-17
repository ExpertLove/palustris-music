import { useEffect } from 'react'
import { useLocation } from 'wouter'
import { applyPalette, DEFAULT_PALETTE, paletteFor } from '../lib/color'
import { useLibrary } from '../store/library'
import { useTaste } from '../store/taste'
import { useUi } from '../store/ui'
import { engine } from './engine'
import { current, usePlayer } from './store'

/** Цвет интерфейса из обложки текущего трека. */
export function usePaletteFromCover() {
  const thumb = usePlayer((s) => current(s)?.thumb ?? null)
  useEffect(() => {
    let alive = true
    if (!thumb) {
      applyPalette(DEFAULT_PALETTE)
      return
    }
    paletteFor(thumb).then((p) => alive && applyPalette(p))
    return () => {
      alive = false
    }
  }, [thumb])
}

const typing = (el: EventTarget | null) => {
  const t = el as HTMLElement | null
  return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)
}

export function useHotkeys() {
  const [, navigate] = useLocation()
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return
      if (typing(e.target)) {
        if (e.key === 'Escape') (e.target as HTMLElement).blur()
        return
      }
      const p = usePlayer.getState()
      const ui = useUi.getState()
      const t = current(p)
      const k = e.key.toLowerCase()
      const role = (e.target as HTMLElement | null)?.getAttribute?.('role')
      if (e.code === 'Space') {
        // Пробел на кнопке нажимает кнопку — не перехватываем.
        if ((e.target as HTMLElement).tagName === 'BUTTON' || role === 'button' || role === 'slider') return
        e.preventDefault()
        p.toggle()
      } else if (e.key === 'ArrowRight' && e.shiftKey) p.next(true)
      else if (e.key === 'ArrowLeft' && e.shiftKey) p.prev()
      else if (e.key === 'ArrowRight' && t && role !== 'slider') p.seek(Math.min(engine.duration, engine.time + 5))
      else if (e.key === 'ArrowLeft' && t && role !== 'slider') p.seek(Math.max(0, engine.time - 5))
      else if (e.key === 'ArrowUp' && role !== 'slider' && !ui.dialog) {
        e.preventDefault()
        p.setVolume(Math.min(1, p.volume + 0.05))
      } else if (e.key === 'ArrowDown' && role !== 'slider' && !ui.dialog) {
        e.preventDefault()
        p.setVolume(Math.max(0, p.volume - 0.05))
      } else if (k === 'm' || k === 'ь') p.toggleMute()
      else if ((k === 'l' || k === 'д') && t) void useLibrary.getState().toggleLike(t)
      else if ((k === 'd' || k === 'в') && t) void useTaste.getState().toggleDislike(t)
      else if (k === 's' || k === 'ы') p.toggleShuffle()
      else if (k === 'r' || k === 'к') p.cycleRepeat()
      else if (k === 'f' || k === 'а') t && ui.setNowPlaying(!ui.nowPlaying)
      else if (k === 'q' || k === 'й') ui.togglePanel('queue')
      else if (k === 't' || k === 'е') ui.togglePanel('lyrics')
      else if (k === 'e' || k === 'у') ui.openDialog(ui.dialog?.kind === 'eq' ? null : { kind: 'eq' })
      else if (e.key === '/' || e.key === '.') {
        e.preventDefault()
        navigate('/search')
        window.setTimeout(() => document.querySelector<HTMLInputElement>('.search-box input')?.focus(), 50)
      } else if (e.key === '?' || e.key === ',') ui.openDialog({ kind: 'shortcuts' })
      else return
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [navigate])
}
