import {
  Disc3,
  Heart,
  Link2,
  ListEnd,
  ListMinus,
  ListPlus,
  ListStart,
  MicVocal,
  Moon,
  Plus,
  Trash2,
  X,
} from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { useLocation } from 'wouter'
import { api } from '../lib/api'
import { firstArtist } from '../lib/format'
import { invalidate, useMobile } from '../lib/hooks'
import type { Track } from '../lib/types'
import { usePlayer } from '../player/store'
import { useLibrary } from '../store/library'
import { toast, useUi } from '../store/ui'
import { Collage, Cover } from './common'
import { Equalizer } from './Equalizer'
import { artistHref } from './TrackList'

export const playlistChanged = (id: number) => window.dispatchEvent(new CustomEvent('playlist-changed', { detail: id }))

export function Dialog({ title, onClose, children, width }: { title: string; onClose: () => void; children: ReactNode; width?: number }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  return (
    <div className="dialog-back" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={title} style={width ? ({ ['--dw' as string]: `${width}px` } as React.CSSProperties) : undefined}>
        <button className="icon-btn close" onClick={onClose} aria-label="Закрыть">
          <X size={20} />
        </button>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  )
}

function TrackMenuView() {
  const menu = useUi((s) => s.menu)
  const close = useUi((s) => s.closeMenu)
  const openDialog = useUi((s) => s.openDialog)
  const mobile = useMobile()
  const ref = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null)
  const [, navigate] = useLocation()
  const liked = useLibrary((s) => (menu ? s.liked.has(menu.track.id) : false))
  const toggleLike = useLibrary((s) => s.toggleLike)
  const bump = useLibrary((s) => s.bumpPlaylist)

  useLayoutEffect(() => {
    if (!menu || mobile || !ref.current) return setPos(null)
    const r = ref.current.getBoundingClientRect()
    const left = Math.min(menu.x, window.innerWidth - r.width - 12)
    const top = menu.y + r.height > window.innerHeight - 12 ? Math.max(12, menu.y - r.height) : menu.y
    setPos({ left: Math.max(12, left), top })
  }, [menu, mobile])

  useEffect(() => {
    if (!menu) return
    const down = (e: Event) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close()
    }
    const key = (e: KeyboardEvent) => e.key === 'Escape' && close()
    const t = window.setTimeout(() => window.addEventListener('pointerdown', down), 0)
    window.addEventListener('keydown', key)
    if (!mobile) window.addEventListener('wheel', close, { passive: true })
    ref.current?.querySelector<HTMLElement>('.menu-item')?.focus()
    return () => {
      window.clearTimeout(t)
      window.removeEventListener('pointerdown', down)
      window.removeEventListener('keydown', key)
      window.removeEventListener('wheel', close)
    }
  }, [menu, close, mobile])

  if (!menu) return null
  const t = menu.track
  const run = (fn: () => void) => () => {
    close()
    fn()
  }
  const p = usePlayer.getState()
  const items: ReactNode[] = [
    <Item key="next" icon={<ListStart size={18} />} label="Играть следующим" onClick={run(() => p.playNext(t))} />,
    <Item key="q" icon={<ListEnd size={18} />} label="Добавить в очередь" onClick={run(() => p.addToQueue(t))} />,
    <div key="s1" className="menu-sep" />,
    <Item key="like" icon={<Heart size={18} fill={liked ? 'currentColor' : 'none'} />} label={liked ? 'Убрать из «Мне нравится»' : 'Мне нравится'} onClick={run(() => toggleLike(t))} />,
    <Item key="pl" icon={<ListPlus size={18} />} label="Добавить в плейлист" onClick={run(() => openDialog({ kind: 'add-to-playlist', track: t }))} />,
  ]
  if (menu.ctx.playlistId !== undefined) {
    const pid = menu.ctx.playlistId
    items.push(
      <Item
        key="rm"
        icon={<ListMinus size={18} />}
        label="Убрать из этого плейлиста"
        onClick={run(async () => {
          try {
            await api.removeFromPlaylist(pid, t.id)
            bump(pid, -1)
            invalidate(`playlist:${pid}`)
            playlistChanged(pid)
            toast('Трек убран из плейлиста')
          } catch (e) {
            toast((e as Error).message)
          }
        })}
      />,
    )
  }
  if (menu.ctx.queueIndex !== undefined) {
    const qi = menu.ctx.queueIndex
    items.push(<Item key="rmq" icon={<Trash2 size={18} />} label="Убрать из очереди" onClick={run(() => p.removeAt(qi))} />)
  }
  items.push(
    <div key="s2" className="menu-sep" />,
    <Item key="ar" icon={<MicVocal size={18} />} label="Перейти к исполнителю" onClick={run(() => navigate(artistHref(firstArtist(t.artist), t.artistId)))} />,
  )
  if (t.albumId) items.push(<Item key="al" icon={<Disc3 size={18} />} label="Перейти к альбому" onClick={run(() => navigate(`/album/${t.albumId}`))} />)
  items.push(
    <Item
      key="share"
      icon={<Link2 size={18} />}
      label="Скопировать ссылку"
      onClick={run(async () => {
        const url = `${location.origin}${import.meta.env.BASE_URL}search?q=${encodeURIComponent(`${firstArtist(t.artist)} ${t.title}`)}`
        try {
          if (navigator.share && mobile) await navigator.share({ title: `${t.artist} — ${t.title}`, url })
          else {
            await navigator.clipboard.writeText(`${t.artist} — ${t.title}\n${url}`)
            toast('Ссылка скопирована')
          }
        } catch {
          /* отменили */
        }
      })}
    />,
  )

  const head = (
    <div className="menu-head">
      <div className="art">
        <Cover src={t.thumb} size={120} label={t.title} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div className="ellipsis" style={{ fontWeight: 650 }}>
          {t.title}
        </div>
        <div className="ellipsis small muted">{t.artist}</div>
      </div>
    </div>
  )

  if (mobile)
    return (
      <>
        <div className="sheet-back" onClick={close} />
        <div className="sheet" ref={ref} role="menu">
          {head}
          {items}
        </div>
      </>
    )
  return (
    <div ref={ref} className="menu" role="menu" style={pos ?? { left: menu.x, top: menu.y, visibility: 'hidden' }}>
      {head}
      {items}
    </div>
  )
}

function Item({ icon, label, onClick, danger }: { icon: ReactNode; label: string; onClick: () => void; danger?: boolean }) {
  return (
    <button type="button" role="menuitem" className={`menu-item ${danger ? 'danger' : ''}`} onClick={onClick}>
      {icon}
      <span className="ellipsis">{label}</span>
    </button>
  )
}

function Toasts() {
  const toasts = useUi((s) => s.toasts)
  const dismiss = useUi((s) => s.dismiss)
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className="toast">
          <span>{t.text}</span>
          {t.action && (
            <button
              onClick={() => {
                t.action!.run()
                dismiss(t.id)
              }}
            >
              {t.action.label}
            </button>
          )}
        </div>
      ))}
    </div>
  )
}

function NewPlaylistDialog({ track, tracks, onClose }: { track?: Track; tracks?: Track[]; onClose: () => void }) {
  const create = useLibrary((s) => s.createPlaylist)
  const [name, setName] = useState(tracks?.length ? '' : track ? `${firstArtist(track.artist)} и не только` : '')
  const [busy, setBusy] = useState(false)
  const [, navigate] = useLocation()
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    setBusy(true)
    const items = tracks ?? (track ? [track] : [])
    const id = await create(name.trim(), items)
    setBusy(false)
    if (id) {
      onClose()
      toast(`Плейлист «${name.trim()}» создан`)
      if (!track) navigate(`/playlist/${id}`)
    }
  }
  return (
    <Dialog title="Новый плейлист" onClose={onClose}>
      <p className="muted small" style={{ marginBottom: 18 }}>
        {tracks?.length ? `Сохраню ${tracks.length} треков в новый плейлист.` : track ? `Первым треком будет «${track.title}».` : 'Название можно поменять потом.'}
      </p>
      <form onSubmit={submit} style={{ display: 'grid', gap: 14 }}>
        <input className="input" autoFocus placeholder="Например, «Для пробежки»" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Отмена
          </button>
          <button className="btn btn-primary" disabled={!name.trim() || busy}>
            Создать
          </button>
        </div>
      </form>
    </Dialog>
  )
}

function AddToPlaylistDialog({ track, onClose }: { track: Track; onClose: () => void }) {
  const playlists = useLibrary((s) => s.playlists)
  const add = useLibrary((s) => s.addToPlaylist)
  const openDialog = useUi((s) => s.openDialog)
  return (
    <Dialog title="Добавить в плейлист" onClose={onClose} width={420}>
      <p className="muted small ellipsis" style={{ marginBottom: 14 }}>
        «{track.title}» — {track.artist}
      </p>
      <div className="menu-scroll" style={{ maxHeight: 360, margin: '0 -10px' }}>
        <button className="menu-item" style={{ height: 60 }} onClick={() => openDialog({ kind: 'new-playlist', track })}>
          <span style={{ width: 44, height: 44, borderRadius: 10, display: 'grid', placeItems: 'center', background: 'rgb(255 255 255 / .08)' }}>
            <Plus size={20} />
          </span>
          <b>Новый плейлист</b>
        </button>
        {playlists.map((p) => (
          <button
            key={p.id}
            className="menu-item"
            style={{ height: 60 }}
            onClick={() => {
              onClose()
              invalidate(`playlist:${p.id}`)
              void add(p.id, track).then(() => playlistChanged(p.id))
            }}
          >
            <span style={{ width: 44, height: 44, borderRadius: 10, overflow: 'hidden', flex: 'none' }}>
              <Collage thumbs={p.thumbs} label={p.name} />
            </span>
            <span style={{ display: 'grid', minWidth: 0 }}>
              <span className="ellipsis" style={{ fontWeight: 600 }}>
                {p.name}
              </span>
              <span className="tiny muted">{p.count} тр.</span>
            </span>
          </button>
        ))}
      </div>
    </Dialog>
  )
}

function SleepDialog({ onClose }: { onClose: () => void }) {
  const sleepAt = usePlayer((s) => s.sleepAt)
  const endOfTrack = usePlayer((s) => s.sleepEndOfTrack)
  const setSleep = usePlayer((s) => s.setSleep)
  const left = sleepAt ? Math.max(0, Math.round((sleepAt - Date.now()) / 60000)) : 0
  const pick = (v: number | 'track' | null) => {
    setSleep(v)
    onClose()
  }
  return (
    <Dialog title="Таймер сна" onClose={onClose} width={400}>
      <p className="muted small" style={{ marginBottom: 16 }}>
        {sleepAt ? `Музыка остановится через ${left} мин.` : endOfTrack ? 'Музыка остановится после текущего трека.' : 'Музыка плавно встанет на паузу в выбранное время.'}
      </p>
      <div style={{ display: 'grid', gap: 2, margin: '0 -10px' }}>
        {[15, 30, 45, 60, 90].map((m) => (
          <button key={m} className="menu-item" onClick={() => pick(m)}>
            <Moon size={18} />
            Через {m} минут
          </button>
        ))}
        <button className="menu-item" onClick={() => pick('track')}>
          <Moon size={18} />
          После этого трека
        </button>
        {(sleepAt || endOfTrack) && (
          <button className="menu-item danger" onClick={() => pick(null)}>
            <X size={18} />
            Выключить таймер
          </button>
        )}
      </div>
    </Dialog>
  )
}

export const SHORTCUTS: [string, string[]][] = [
  ['Играть / пауза', ['Пробел']],
  ['Следующий трек', ['Shift', '→']],
  ['Предыдущий трек', ['Shift', '←']],
  ['Перемотка на 5 секунд', ['←', '→']],
  ['Громкость', ['↑', '↓']],
  ['Без звука', ['M']],
  ['Мне нравится', ['L']],
  ['Перемешать', ['S']],
  ['Повтор', ['R']],
  ['Поиск', ['/']],
  ['Полноэкранный плеер', ['F']],
  ['Очередь', ['Q']],
  ['Текст песни', ['T']],
  ['Эквалайзер', ['E']],
  ['Эта подсказка', ['?']],
]

function ShortcutsDialog({ onClose }: { onClose: () => void }) {
  return (
    <Dialog title="Горячие клавиши" onClose={onClose} width={460}>
      <div className="shortcuts" style={{ marginTop: 10 }}>
        {SHORTCUTS.map(([label, keys]) => (
          <div key={label}>
            <span>{label}</span>
            <span>
              {keys.map((k) => (
                <kbd key={k} className="kbd">
                  {k}
                </kbd>
              ))}
            </span>
          </div>
        ))}
      </div>
    </Dialog>
  )
}

export function Overlays() {
  const dialog = useUi((s) => s.dialog)
  const close = useUi((s) => s.closeDialog)
  return (
    <>
      <TrackMenuView />
      <Toasts />
      {dialog?.kind === 'eq' && (
        <Dialog title="Эквалайзер" onClose={close} width={640}>
          <Equalizer />
        </Dialog>
      )}
      {dialog?.kind === 'sleep' && <SleepDialog onClose={close} />}
      {dialog?.kind === 'shortcuts' && <ShortcutsDialog onClose={close} />}
      {dialog?.kind === 'new-playlist' && <NewPlaylistDialog track={dialog.track} tracks={dialog.tracks} onClose={close} />}
      {dialog?.kind === 'add-to-playlist' && <AddToPlaylistDialog track={dialog.track} onClose={close} />}
    </>
  )
}
