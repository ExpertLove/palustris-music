import { useRef, useState } from 'react'
import { fmtTime } from '../lib/format'
import { engine } from '../player/engine'
import { usePlayer } from '../player/store'

/** Полоса перемотки: перетаскивание, подсказка времени, клавиатура, загруженная часть. */
export function Seek({ className = '' }: { className?: string }) {
  const time = usePlayer((s) => s.time)
  const duration = usePlayer((s) => s.duration)
  const buffering = usePlayer((s) => s.buffering)
  const seek = usePlayer((s) => s.seek)
  const ref = useRef<HTMLDivElement>(null)
  const [drag, setDrag] = useState<number | null>(null)
  const [hover, setHover] = useState(0)

  const frac = (clientX: number) => {
    const r = ref.current?.getBoundingClientRect()
    if (!r || r.width === 0) return 0
    return Math.min(1, Math.max(0, (clientX - r.left) / r.width))
  }
  const shown = drag ?? time
  const p = duration > 0 ? (shown / duration) * 100 : 0
  const b = duration > 0 ? Math.min(100, (engine.buffered() / duration) * 100) : 0

  return (
    <div
      ref={ref}
      className={`seek ${drag !== null ? 'dragging' : ''} ${buffering ? 'loading' : ''} ${className}`}
      role="slider"
      tabIndex={0}
      aria-label="Перемотка"
      aria-valuemin={0}
      aria-valuemax={Math.round(duration)}
      aria-valuenow={Math.round(shown)}
      aria-valuetext={`${fmtTime(shown)} из ${fmtTime(duration)}`}
      style={{ ['--p' as string]: `${p}%`, ['--b' as string]: `${b}%`, ['--h' as string]: `${hover * 100}%` }}
      onPointerDown={(e) => {
        if (duration <= 0) return
        e.currentTarget.setPointerCapture(e.pointerId)
        setDrag(frac(e.clientX) * duration)
      }}
      onPointerMove={(e) => {
        const f = frac(e.clientX)
        setHover(f)
        if (drag !== null) setDrag(f * duration)
      }}
      onPointerUp={() => {
        if (drag !== null) seek(drag)
        setDrag(null)
      }}
      onPointerCancel={() => setDrag(null)}
      onKeyDown={(e) => {
        if (e.key === 'ArrowRight') seek(Math.min(duration, time + 5))
        else if (e.key === 'ArrowLeft') seek(Math.max(0, time - 5))
        else if (e.key === 'Home') seek(0)
        else return
        e.preventDefault()
        e.stopPropagation()
      }}
    >
      <div className="seek-track">
        <div className="seek-buf" />
        <div className="seek-fill" />
      </div>
      <div className="seek-knob" />
      <div className="seek-tip">{fmtTime((drag !== null ? drag / Math.max(duration, 1) : hover) * duration)}</div>
    </div>
  )
}

export function Volume({ width }: { width?: number }) {
  const volume = usePlayer((s) => s.volume)
  const muted = usePlayer((s) => s.muted)
  const setVolume = usePlayer((s) => s.setVolume)
  const v = muted ? 0 : volume
  return (
    <input
      className="range"
      type="range"
      min={0}
      max={1}
      step={0.01}
      value={v}
      aria-label="Громкость"
      style={{ ['--p' as string]: `${v * 100}%`, width }}
      onChange={(e) => setVolume(Number(e.target.value))}
    />
  )
}
