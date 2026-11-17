import { Heart, Pause, Play, ThumbsDown } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { bigThumb, initials, isVideoThumb } from '../lib/format'
import type { Track } from '../lib/types'
import { useLibrary } from '../store/library'
import { useTaste } from '../store/taste'

const hues = ['#3a3550', '#29404a', '#4a3530', '#2f4532', '#45304a', '#3d3d2a']
const hueFor = (s: string) => hues[[...s].reduce((a, c) => a + c.charCodeAt(0), 0) % hues.length]

export function Cover({ src, alt = '', size = 300, label, eager }: { src?: string | null; alt?: string; size?: number; label?: string; eager?: boolean }) {
  const [ready, setReady] = useState(false)
  const [failed, setFailed] = useState(false)
  const url = bigThumb(src, size)
  const show = url && !failed
  return (
    <div className="cover">
      {(!show || !ready) && (
        <div className="ph" style={{ ['--c1' as string]: hueFor(label || alt || 'x') }} aria-hidden>
          {!show ? initials(label || alt) : null}
        </div>
      )}
      {show && (
        <img
          src={url}
          alt={alt}
          loading={eager ? 'eager' : 'lazy'}
          decoding="async"
          crossOrigin="anonymous"
          className={`${ready ? 'ready' : ''} ${isVideoThumb(url) ? 'video' : ''}`}
          onLoad={() => setReady(true)}
          onError={() => setFailed(true)}
          draggable={false}
        />
      )}
    </div>
  )
}

/** Обложка плейлиста: четыре первых обложки или одна. */
export function Collage({ thumbs, label }: { thumbs: string[]; label: string }) {
  if (thumbs.length >= 4)
    return (
      <div className="collage">
        {thumbs.slice(0, 4).map((t) => (
          <Cover key={t} src={t} size={200} label={label} />
        ))}
      </div>
    )
  return <Cover src={thumbs[0]} size={400} label={label} />
}

export function LikeButton({ track, size = 20, className = '' }: { track: Track; size?: number; className?: string }) {
  const liked = useLibrary((s) => s.liked.has(track.id))
  const toggle = useLibrary((s) => s.toggleLike)
  const [pop, setPop] = useState(false)
  return (
    <button
      type="button"
      className={`icon-btn like ${liked ? 'on' : ''} ${className}`}
      aria-pressed={liked}
      aria-label={liked ? 'Убрать из «Мне нравится»' : 'Добавить в «Мне нравится»'}
      title={liked ? 'Убрать из «Мне нравится»' : 'Мне нравится'}
      onClick={(e) => {
        e.stopPropagation()
        if (!liked) setPop(true)
        void toggle(track)
      }}
      onAnimationEnd={() => setPop(false)}
    >
      <Heart size={size} fill={liked ? 'currentColor' : 'none'} className={pop ? 'like-pop' : ''} />
    </button>
  )
}

/** «Не нравится» рядом с лайком — в плеере, когда играет волна, радио или микс. Трек сразу переключается. */
export function DislikeButton({ track, size = 20, className = '' }: { track: Track; size?: number; className?: string }) {
  const on = useTaste((s) => s.disliked.has(track.id))
  const toggle = useTaste((s) => s.toggleDislike)
  return (
    <button
      type="button"
      className={`icon-btn dislike ${on ? 'on' : ''} ${className}`}
      aria-pressed={on}
      aria-label={on ? 'Убрать из «Не нравится»' : 'Не нравится'}
      title={on ? 'Убрать из «Не нравится»' : 'Не нравится (D)'}
      onClick={(e) => {
        e.stopPropagation()
        void toggle(track)
      }}
    >
      <ThumbsDown size={size} fill={on ? 'currentColor' : 'none'} />
    </button>
  )
}

export function PlayIcon({ playing, size = 22 }: { playing: boolean; size?: number }) {
  return playing ? <Pause size={size} fill="currentColor" strokeWidth={0} /> : <Play size={size} fill="currentColor" strokeWidth={0} style={{ marginLeft: size * 0.1 }} />
}

export function PlayFab({
  playing,
  loading,
  onClick,
  className = '',
  size = 24,
  label,
}: {
  playing: boolean
  loading?: boolean
  onClick: (e: React.MouseEvent) => void
  className?: string
  size?: number
  label?: string
}) {
  return (
    <button type="button" className={`play-fab ${className}`} onClick={onClick} aria-label={label ?? (playing ? 'Пауза' : 'Играть')} title={playing ? 'Пауза' : 'Играть'}>
      {loading && <span className="spin" aria-hidden />}
      <PlayIcon playing={playing} size={size} />
    </button>
  )
}

export function EqBars({ paused }: { paused?: boolean }) {
  return (
    <span className={`eqbars ${paused ? 'paused' : ''}`} aria-hidden>
      <i />
      <i />
      <i />
    </span>
  )
}

export function Empty({ icon, title, text, children }: { icon: ReactNode; title: string; text?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="ico">{icon}</div>
      <h3>{title}</h3>
      {text && <p>{text}</p>}
      {children}
    </div>
  )
}

export function RowsSkeleton({ n = 8 }: { n?: number }) {
  return (
    <div className="tracks" aria-busy>
      {Array.from({ length: n }, (_, i) => (
        <div key={i} className="trow no-album" style={{ cursor: 'default' }}>
          <span />
          <div className="sk" style={{ width: 48, height: 48 }} />
          <div style={{ display: 'grid', gap: 8 }}>
            <div className="sk" style={{ height: 13, width: `${40 + ((i * 37) % 40)}%` }} />
            <div className="sk" style={{ height: 11, width: `${20 + ((i * 23) % 25)}%` }} />
          </div>
        </div>
      ))}
    </div>
  )
}

export function CardsSkeleton({ n = 6, round }: { n?: number; round?: boolean }) {
  return (
    <div className="shelf-row" aria-busy>
      {Array.from({ length: n }, (_, i) => (
        <div key={i} style={{ display: 'grid', gap: 10 }}>
          <div className="sk" style={{ aspectRatio: '1', borderRadius: round ? '50%' : 14 }} />
          <div className="sk" style={{ height: 13, width: '70%' }} />
        </div>
      ))}
    </div>
  )
}

export function ErrorNote({ text, retry }: { text: string; retry?: () => void }) {
  return (
    <div className="empty">
      <h3>Не загрузилось</h3>
      <p>{text}</p>
      {retry && (
        <button className="btn btn-ghost" onClick={retry}>
          Попробовать ещё раз
        </button>
      )}
    </div>
  )
}

export function Logo({ className = 'brand-mark' }: { className?: string }) {
  // Пять столбиков эквалайзера складываются в «М».
  return (
    <svg className={className} viewBox="0 0 32 32" fill="currentColor" aria-hidden>
      <rect x="2.4" y="5" width="4.4" height="22" rx="2.2" />
      <rect x="8.2" y="9" width="4.4" height="11" rx="2.2" />
      <rect x="13.8" y="13" width="4.4" height="14" rx="2.2" />
      <rect x="19.4" y="9" width="4.4" height="11" rx="2.2" />
      <rect x="25.2" y="5" width="4.4" height="22" rx="2.2" />
    </svg>
  )
}
