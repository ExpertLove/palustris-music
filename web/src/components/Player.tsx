import {
  ChevronDown,
  Ellipsis,
  FileText,
  ListMusic,
  Maximize2,
  Moon,
  Repeat,
  Repeat1,
  Shuffle,
  SkipBack,
  SkipForward,
  SlidersHorizontal,
  Volume1,
  Volume2,
  VolumeX,
  X,
} from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'wouter'
import { bigThumb, fmtTime } from '../lib/format'
import { useMobile } from '../lib/hooks'
import { engine } from '../player/engine'
import { current, usePlayer } from '../player/store'
import { useUi } from '../store/ui'
import { Cover, LikeButton, PlayFab, PlayIcon } from './common'
import { Lyrics, Queue } from './LyricsQueue'
import { Seek, Volume } from './Seek'
import { ArtistLinks } from './TrackList'

function Controls({ big }: { big?: boolean }) {
  const playing = usePlayer((s) => s.playing)
  const buffering = usePlayer((s) => s.buffering)
  const shuffle = usePlayer((s) => s.shuffle)
  const repeat = usePlayer((s) => s.repeat)
  const p = usePlayer.getState()
  const sz = big ? 28 : 20
  return (
    <>
      <button className={`icon-btn ${shuffle ? 'on' : 'dim'}`} onClick={p.toggleShuffle} aria-pressed={shuffle} aria-label="Перемешать" title="Перемешать (S)">
        <Shuffle size={big ? 22 : 18} />
        {shuffle && <span className="badge" />}
      </button>
      <button className={`icon-btn ${big ? 'lg' : ''}`} onClick={p.prev} aria-label="Предыдущий" title="Предыдущий (Shift+←)">
        <SkipBack size={sz} fill="currentColor" />
      </button>
      <PlayFab playing={playing} loading={buffering && playing !== false} onClick={() => p.toggle()} className={big ? 'xl light' : 'sm light'} size={big ? 30 : 20} />
      <button className={`icon-btn ${big ? 'lg' : ''}`} onClick={() => p.next(true)} aria-label="Следующий" title="Следующий (Shift+→)">
        <SkipForward size={sz} fill="currentColor" />
      </button>
      <button className={`icon-btn ${repeat !== 'off' ? 'on' : 'dim'}`} onClick={p.cycleRepeat} aria-label={`Повтор: ${repeat === 'off' ? 'выкл' : repeat === 'all' ? 'очередь' : 'трек'}`} title="Повтор (R)">
        {repeat === 'one' ? <Repeat1 size={big ? 22 : 18} /> : <Repeat size={big ? 22 : 18} />}
        {repeat !== 'off' && <span className="badge" />}
      </button>
    </>
  )
}

function MuteButton() {
  const muted = usePlayer((s) => s.muted)
  const volume = usePlayer((s) => s.volume)
  const toggleMute = usePlayer((s) => s.toggleMute)
  const Icon = muted || volume === 0 ? VolumeX : volume < 0.5 ? Volume1 : Volume2
  return (
    <button className="icon-btn sm" onClick={toggleMute} aria-label={muted ? 'Включить звук' : 'Выключить звук'} title="Без звука (M)">
      <Icon size={18} />
    </button>
  )
}

export function Dock() {
  const track = usePlayer((s) => current(s))
  const time = usePlayer((s) => s.time)
  const duration = usePlayer((s) => s.duration)
  const eqOn = usePlayer((s) => s.eq.on)
  const sleep = usePlayer((s) => !!s.sleepAt || s.sleepEndOfTrack)
  const panel = useUi((s) => s.panel)
  const togglePanel = useUi((s) => s.togglePanel)
  const openDialog = useUi((s) => s.openDialog)
  const setNP = useUi((s) => s.setNowPlaying)
  const openMenu = useUi((s) => s.openMenu)
  if (!track) return null
  return (
    <div className="dock" role="region" aria-label="Плеер">
      <div className="dock-now">
        <button className="dock-cover" onClick={() => setNP(true)} aria-label="Открыть плеер на весь экран" title="На весь экран (F)">
          <Cover src={track.thumb} size={160} label={track.title} eager />
          <span className="expand">
            <Maximize2 size={20} />
          </span>
        </button>
        <div className="dock-meta">
          <button className="t ellipsis" style={{ textAlign: 'left' }} onClick={() => setNP(true)}>
            {track.title}
          </button>
          <span className="a">
            <ArtistLinks track={track} stop={false} />
          </span>
          {track.why && <span className="why ellipsis">{track.why}</span>}
        </div>
        <LikeButton track={track} size={19} />
      </div>
      <div className="dock-center">
        <div className="dock-buttons">
          <Controls />
        </div>
        <div className="dock-seek">
          <span className="tm">{fmtTime(time)}</span>
          <Seek />
          <span className="tm">{fmtTime(duration)}</span>
        </div>
      </div>
      <div className="dock-right">
        <button className={`icon-btn sm ${panel === 'lyrics' ? 'on' : ''}`} onClick={() => togglePanel('lyrics')} aria-pressed={panel === 'lyrics'} aria-label="Текст песни" title="Текст (T)">
          <FileText size={18} />
        </button>
        <button className={`icon-btn sm ${panel === 'queue' ? 'on' : ''}`} onClick={() => togglePanel('queue')} aria-pressed={panel === 'queue'} aria-label="Очередь" title="Очередь (Q)">
          <ListMusic size={18} />
        </button>
        <button className={`icon-btn sm ${eqOn ? 'on' : ''}`} onClick={() => openDialog({ kind: 'eq' })} aria-label="Эквалайзер" title="Эквалайзер (E)">
          <SlidersHorizontal size={18} />
        </button>
        <button className={`icon-btn sm ${sleep ? 'on' : ''}`} onClick={() => openDialog({ kind: 'sleep' })} aria-label="Таймер сна" title="Таймер сна">
          <Moon size={18} />
        </button>
        <div className="dock-vol">
          <MuteButton />
          <Volume />
        </div>
        <button
          className="icon-btn sm"
          aria-label="Ещё"
          onClick={(e) => {
            const r = e.currentTarget.getBoundingClientRect()
            openMenu({ track, x: r.left - 200, y: r.top - 340, ctx: {} })
          }}
        >
          <Ellipsis size={18} />
        </button>
      </div>
    </div>
  )
}

export function MiniPlayer() {
  const track = usePlayer((s) => current(s))
  const playing = usePlayer((s) => s.playing)
  const time = usePlayer((s) => s.time)
  const duration = usePlayer((s) => s.duration)
  const toggle = usePlayer((s) => s.toggle)
  const next = usePlayer((s) => s.next)
  const setNP = useUi((s) => s.setNowPlaying)
  const start = useRef<{ x: number; y: number } | null>(null)
  if (!track) return null
  return (
    <div
      className="mini"
      onClick={() => setNP(true)}
      onPointerDown={(e) => (start.current = { x: e.clientX, y: e.clientY })}
      onPointerUp={(e) => {
        const s = start.current
        start.current = null
        if (!s) return
        const dx = e.clientX - s.x
        if (Math.abs(dx) > 70 && Math.abs(e.clientY - s.y) < 40) {
          e.preventDefault()
          if (dx < 0) next(true)
          else usePlayer.getState().prev()
        } else if (s.y - e.clientY > 40) setNP(true)
      }}
      role="button"
      aria-label="Открыть плеер"
    >
      <div className="mini-cover">
        <Cover src={track.thumb} size={120} label={track.title} eager />
      </div>
      <div className="mini-meta">
        <span className="t ellipsis">{track.title}</span>
        <span className="a ellipsis">{track.artist}</span>
      </div>
      <LikeButton track={track} size={20} />
      <button
        className="icon-btn"
        style={{ color: 'var(--ink)' }}
        onClick={(e) => {
          e.stopPropagation()
          toggle()
        }}
        aria-label={playing ? 'Пауза' : 'Играть'}
      >
        <PlayIcon playing={playing} size={22} />
      </button>
      <div className="mini-progress">
        <i style={{ width: `${duration ? (time / duration) * 100 : 0}%` }} />
      </div>
    </div>
  )
}

export function SidePanel() {
  const panel = useUi((s) => s.panel)
  const setPanel = useUi((s) => s.setPanel)
  const has = usePlayer((s) => s.index >= 0)
  if (!panel || !has) return null
  return (
    <aside className="panel" aria-label={panel === 'queue' ? 'Очередь' : 'Текст песни'}>
      <div className="panel-head">
        <div className="seg" role="tablist">
          <button role="tab" aria-selected={panel === 'queue'} onClick={() => setPanel('queue')}>
            Очередь
          </button>
          <button role="tab" aria-selected={panel === 'lyrics'} onClick={() => setPanel('lyrics')}>
            Текст
          </button>
        </div>
        <span style={{ flex: 1 }} />
        <button className="icon-btn sm" onClick={() => setPanel(null)} aria-label="Закрыть панель">
          <X size={18} />
        </button>
      </div>
      <div className="panel-body">{panel === 'queue' ? <Queue /> : <Lyrics />}</div>
    </aside>
  )
}

function Visualizer() {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const cv = ref.current
    const an = engine.analyser
    if (!cv || !an) return
    const g = cv.getContext('2d')
    if (!g) return
    const data = new Uint8Array(an.frequencyBinCount)
    let raf = 0
    let color = '#fff'
    let tick = 0
    const draw = () => {
      raf = requestAnimationFrame(draw)
      if (tick++ % 30 === 0) color = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#fff'
      const w = (cv.width = cv.clientWidth * devicePixelRatio)
      const h = (cv.height = cv.clientHeight * devicePixelRatio)
      an.getByteFrequencyData(data)
      g.clearRect(0, 0, w, h)
      const bars = 48
      const bw = w / bars
      const grad = g.createLinearGradient(0, h, 0, 0)
      grad.addColorStop(0, color)
      grad.addColorStop(1, 'transparent')
      g.fillStyle = grad
      for (let i = 0; i < bars; i++) {
        // Логарифмическая раскладка: басы не съедают половину ширины.
        const idx = Math.floor(Math.pow(i / bars, 2) * (data.length * 0.62)) + 1
        // Верхние частоты тише по природе — подтягиваем, чтобы столбики жили по всей ширине.
        const v = Math.min(1, (data[idx] / 255) * (1 + (i / bars) * 0.9))
        const bh = Math.max(2, v * v * h)
        const x = i * bw + bw * 0.18
        g.beginPath()
        g.roundRect(x, h - bh, bw * 0.64, bh, bw * 0.3)
        g.fill()
      }
    }
    draw()
    return () => cancelAnimationFrame(raf)
  }, [])
  if (!engine.analyser) return null
  return <canvas ref={ref} className="np-viz" aria-hidden />
}

export function NowPlaying() {
  const open = useUi((s) => s.nowPlaying)
  const setNP = useUi((s) => s.setNowPlaying)
  const openDialog = useUi((s) => s.openDialog)
  const openMenu = useUi((s) => s.openMenu)
  const track = usePlayer((s) => current(s))
  const playing = usePlayer((s) => s.playing)
  const time = usePlayer((s) => s.time)
  const duration = usePlayer((s) => s.duration)
  const source = usePlayer((s) => s.source)
  const eqOn = usePlayer((s) => s.eq.on)
  const sleep = usePlayer((s) => !!s.sleepAt || s.sleepEndOfTrack)
  const mobile = useMobile()
  const [tab, setTab] = useState<'queue' | 'lyrics'>('lyrics')
  const [mobileSide, setMobileSide] = useState(false)
  const [closing, setClosing] = useState(false)
  const [bgReady, setBgReady] = useState(false)
  const drag = useRef<number | null>(null)

  useEffect(() => {
    if (!open) return
    const k = (e: KeyboardEvent) => e.key === 'Escape' && close()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  })
  useEffect(() => setBgReady(false), [track?.thumb])

  if (!open || !track) return null
  const close = () => {
    setClosing(true)
    window.setTimeout(() => {
      setClosing(false)
      setMobileSide(false)
      setNP(false)
    }, 260)
  }

  return (
    <div
      className={`np ${closing ? 'closing' : ''}`}
      role="dialog"
      aria-modal="true"
      aria-label="Сейчас играет"
      onPointerDown={(e) => {
        if (mobile && (e.target as HTMLElement).closest('.np-art, .np-head')) drag.current = e.clientY
      }}
      onPointerUp={(e) => {
        if (drag.current !== null && e.clientY - drag.current > 90) close()
        drag.current = null
      }}
    >
      <div className="np-bg" aria-hidden>
        {track.thumb && <img src={bigThumb(track.thumb, 300) ?? ''} alt="" className={bgReady ? 'ready' : ''} onLoad={() => setBgReady(true)} crossOrigin="anonymous" />}
      </div>
      <div className="np-head">
        <button className="icon-btn" onClick={close} aria-label="Свернуть">
          <ChevronDown size={26} />
        </button>
        <div className="np-src">
          <span>{source?.kind === 'wave' ? 'Играет' : 'Играет из'}</span>
          {source?.href ? (
            <Link href={source.href} onClick={close}>
              <b className="ellipsis">{source.title || 'очереди'}</b>
            </Link>
          ) : (
            <b className="ellipsis">{source?.title || 'очереди'}</b>
          )}
        </div>
        <button
          className="icon-btn"
          aria-label="Ещё"
          onClick={(e) => {
            const r = e.currentTarget.getBoundingClientRect()
            openMenu({ track, x: r.left - 220, y: r.bottom + 6, ctx: {} })
          }}
        >
          <Ellipsis size={22} />
        </button>
      </div>
      <div className="np-body">
        <div className="np-main">
          <div className={`np-art ${playing ? '' : 'paused'}`}>
            <Cover src={track.thumb} size={900} label={track.title} eager />
            <Visualizer />
          </div>
          <div className="np-info">
            <div style={{ minWidth: 0 }}>
              <div className="t ellipsis">{track.title}</div>
              <div className="a ellipsis" onClick={close}>
                <ArtistLinks track={track} stop={false} />
              </div>
              {track.why && <div className="why">{track.why}</div>}
            </div>
            <LikeButton track={track} size={24} />
          </div>
          <div className="np-seek">
            <Seek />
            <div className="times">
              <span>{fmtTime(time)}</span>
              <span>−{fmtTime(Math.max(0, duration - time))}</span>
            </div>
          </div>
          <div className="np-controls">
            <Controls big />
          </div>
          <div className="np-extra">
            <button className={`icon-btn ${eqOn ? 'on' : ''}`} onClick={() => openDialog({ kind: 'eq' })} aria-label="Эквалайзер">
              <SlidersHorizontal size={20} />
            </button>
            {!mobile && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, maxWidth: 260 }}>
                <MuteButton />
                <Volume />
              </div>
            )}
            <button className={`icon-btn ${sleep ? 'on' : ''}`} onClick={() => openDialog({ kind: 'sleep' })} aria-label="Таймер сна">
              <Moon size={20} />
            </button>
          </div>
        </div>
        <div className={`np-side ${mobileSide ? 'show' : ''}`}>
          <div className="panel-tabs">
            <div className="seg" role="tablist">
              <button role="tab" aria-selected={tab === 'lyrics'} onClick={() => setTab('lyrics')}>
                Текст
              </button>
              <button role="tab" aria-selected={tab === 'queue'} onClick={() => setTab('queue')}>
                Очередь
              </button>
            </div>
          </div>
          <div className="panel-body">{tab === 'lyrics' ? <Lyrics /> : <Queue />}</div>
        </div>
      </div>
      <div className="np-mtabs">
        <button className={`icon-btn lg ${mobileSide && tab === 'lyrics' ? 'on' : ''}`} onClick={() => (mobileSide && tab === 'lyrics' ? setMobileSide(false) : (setTab('lyrics'), setMobileSide(true)))} aria-label="Текст песни">
          <FileText size={22} />
        </button>
        <button className={`icon-btn lg ${mobileSide && tab === 'queue' ? 'on' : ''}`} onClick={() => (mobileSide && tab === 'queue' ? setMobileSide(false) : (setTab('queue'), setMobileSide(true)))} aria-label="Очередь">
          <ListMusic size={22} />
        </button>
      </div>
    </div>
  )
}
