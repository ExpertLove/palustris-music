import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'wouter'
import { api } from '../lib/api'
import type { Mood } from '../lib/data'
import type { AlbumCard, ArtistCard, Track } from '../lib/types'
import { usePlayer, type Source } from '../player/store'
import { toast } from '../store/ui'
import { Cover, PlayFab } from './common'

export function Shelf({ title, more, children, cardW }: { title?: ReactNode; more?: { href: string; label?: string }; children: ReactNode; cardW?: number }) {
  const row = useRef<HTMLDivElement>(null)
  const [edges, setEdges] = useState({ l: false, r: false })
  const update = () => {
    const el = row.current
    if (!el) return
    setEdges({ l: el.scrollLeft > 8, r: el.scrollLeft + el.clientWidth < el.scrollWidth - 8 })
  }
  useEffect(() => {
    update()
    const el = row.current
    if (!el) return
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [children])
  const by = (dir: number) => row.current?.scrollBy({ left: dir * row.current.clientWidth * 0.85, behavior: 'smooth' })
  return (
    <section className="section shelf" style={cardW ? ({ ['--card-w' as string]: `${cardW}px` } as React.CSSProperties) : undefined}>
      {(title || more) && (
        <div className="section-head">
          {title && <h2>{title}</h2>}
          {more && (
            <Link className="more-link" href={more.href}>
              {more.label ?? 'Все'}
            </Link>
          )}
        </div>
      )}
      <button className="icon-btn shelf-nav l" disabled={!edges.l} onClick={() => by(-1)} aria-label="Назад">
        <ChevronLeft size={20} />
      </button>
      <div className="shelf-row" ref={row} onScroll={update}>
        {children}
      </div>
      <button className="icon-btn shelf-nav r" disabled={!edges.r} onClick={() => by(1)} aria-label="Вперёд">
        <ChevronRight size={20} />
      </button>
    </section>
  )
}

/** Карточка с кнопкой «играть», которая сама подгружает треки. */
export function MediaCard({
  href,
  title,
  sub,
  art,
  round,
  load,
  source,
}: {
  href: string
  title: string
  sub?: string
  art: ReactNode
  round?: boolean
  load?: () => Promise<Track[]>
  source?: Source
}) {
  const playList = usePlayer((s) => s.playList)
  const [busy, setBusy] = useState(false)
  const play = async (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (!load) return
    setBusy(true)
    try {
      const list = await load()
      if (list.length) playList(list, 0, source ?? { kind: 'list', title, href })
      else toast('Здесь пока нет треков')
    } catch (err) {
      toast((err as Error).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Link href={href} className={`card ${round ? 'round' : ''}`}>
      <div className="card-art">
        {art}
        {load && (
          <span className="card-play">
            <PlayFab playing={false} loading={busy} onClick={play} className="sm" size={18} label={`Играть «${title}»`} />
          </span>
        )}
      </div>
      <div style={{ display: 'grid', gap: 2, minWidth: 0 }}>
        <span className="t ellipsis">{title}</span>
        {sub && <span className="s ellipsis">{sub}</span>}
      </div>
    </Link>
  )
}

const kindLabel = (k: AlbumCard['kind']) => (k === 'single' ? 'Сингл' : k === 'playlist' ? 'Плейлист' : 'Альбом')

export function AlbumCardView({ a, showArtist = true }: { a: AlbumCard; showArtist?: boolean }) {
  const sub = [kindLabel(a.kind), showArtist && a.kind !== 'playlist' ? a.artist : null, a.sub && !a.sub.includes(a.artist) ? a.sub : a.sub.replace(a.artist, '').replace(/^ · /, '')]
    .filter(Boolean)
    .join(' · ')
  return (
    <MediaCard
      href={`/album/${a.id}`}
      title={a.title}
      sub={sub}
      art={<Cover src={a.thumb} size={400} label={a.title} />}
      load={() => api.collection(a.id).then((c) => c.tracks)}
    />
  )
}

export function ArtistCardView({ a }: { a: ArtistCard }) {
  return (
    <MediaCard
      round
      href={`/artist/${a.id}`}
      title={a.name}
      sub={a.sub || 'Исполнитель'}
      art={<Cover src={a.thumb} size={400} label={a.name} />}
      load={() => api.artist({ id: a.id }).then((p) => p.tracks)}
    />
  )
}

export function MoodCard({ m }: { m: Mood }) {
  const Icon = m.icon
  return (
    <Link href={`/mood/${m.id}`} className="mood" style={{ ['--from' as string]: m.from, ['--to' as string]: m.to }}>
      <Icon className="ico" size={120} strokeWidth={1.4} aria-hidden />
      <b>{m.title}</b>
      <span>{m.sub}</span>
    </Link>
  )
}
