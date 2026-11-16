import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core'
import { SortableContext, arrayMove, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { Clock3, Ellipsis, GripVertical, Play } from 'lucide-react'
import { Fragment, memo, type CSSProperties, type ReactNode } from 'react'
import { Link } from 'wouter'
import { fmtTime } from '../lib/format'
import { useLongPress } from '../lib/hooks'
import type { Track } from '../lib/types'
import { current, usePlayer, type Source } from '../player/store'
import { useUi, type TrackMenuCtx } from '../store/ui'
import { Cover, EqBars, LikeButton } from './common'

export const artistHref = (name: string, id?: string | null) => (id ? `/artist/${id}` : `/artist/n/${encodeURIComponent(name.trim())}`)

/** «A, B & C» → ссылки на каждого исполнителя (id знаем только у первого). */
export function ArtistLinks({ track, stop = true }: { track: Pick<Track, 'artist' | 'artistId'>; stop?: boolean }) {
  const parts = track.artist.split(/, | & /).filter(Boolean)
  return (
    <span className="ellipsis">
      {parts.map((p, i) => (
        <Fragment key={i}>
          {i > 0 && ', '}
          <Link href={artistHref(p, i === 0 ? track.artistId : null)} onClick={(e) => stop && e.stopPropagation()}>
            {p}
          </Link>
        </Fragment>
      ))}
    </span>
  )
}

interface RowProps {
  track: Track
  n: number
  onPlay: () => void
  ctx?: TrackMenuCtx
  showAlbum?: boolean
  showWhy?: boolean
  numLabel?: ReactNode
  className?: string
  drag?: { attributes: object; listeners: object | undefined; setRef: (el: HTMLElement | null) => void; style: CSSProperties; dragging: boolean }
}

export const TrackRow = memo(function TrackRow({ track, n, onPlay, ctx = {}, showAlbum, showWhy, numLabel, className = '', drag }: RowProps) {
  const isCurrent = usePlayer((s) => current(s)?.id === track.id)
  const playing = usePlayer((s) => s.playing)
  const openMenu = useUi((s) => s.openMenu)
  const menuOpen = useUi((s) => s.menu?.track.id === track.id && s.menu?.ctx.queueIndex === ctx.queueIndex)
  const lp = useLongPress((x, y) => openMenu({ track, x, y, ctx }))

  const menu = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
    openMenu({ track, x: e.type === 'contextmenu' ? e.clientX : r.right - 8, y: e.type === 'contextmenu' ? e.clientY : r.bottom, ctx })
  }

  return (
    <div
      ref={drag?.setRef}
      style={drag?.style}
      className={`trow ${showAlbum ? '' : 'no-album'} ${isCurrent ? 'current' : ''} ${menuOpen ? 'menu-open' : ''} ${drag?.dragging ? 'dragging' : ''} ${className}`}
      role="button"
      tabIndex={0}
      aria-label={`${track.title} — ${track.artist}`}
      onClick={() => !lp.wasLongPress() && onPlay()}
      onKeyDown={(e) => {
        if (e.target !== e.currentTarget) return
        if (e.key === 'Enter') onPlay()
        if (e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) menu(e as unknown as React.MouseEvent)
      }}
      onContextMenu={menu}
      onPointerDown={lp.onPointerDown}
      onPointerUp={lp.onPointerUp}
      onPointerLeave={lp.onPointerLeave}
      onPointerMove={lp.onPointerMove}
      {...(drag?.attributes ?? {})}
    >
      <span className="n">
        {drag ? (
          <span className="grip" {...(drag.listeners ?? {})} onClick={(e) => e.stopPropagation()} aria-label="Перетащить">
            <GripVertical size={16} />
          </span>
        ) : (
          <>
            <span className="num-label">{numLabel ?? n}</span>
            <span className="play">{isCurrent && playing ? <EqBars /> : <Play size={15} fill="currentColor" strokeWidth={0} />}</span>
            {isCurrent && <EqBars paused={!playing} />}
          </>
        )}
      </span>
      <div className="art">
        <Cover src={track.thumb} size={120} label={track.title} />
      </div>
      <div className="meta">
        <span className="t ellipsis">{track.title}</span>
        <span className="s">
          <ArtistLinks track={track} />
          {showWhy && track.why && <span className="why ellipsis">· {track.why}</span>}
        </span>
      </div>
      {showAlbum && (
        <span className="alb ellipsis">
          {track.album ? (
            track.albumId ? (
              <Link href={`/album/${track.albumId}`} onClick={(e) => e.stopPropagation()}>
                {track.album}
              </Link>
            ) : (
              track.album
            )
          ) : null}
        </span>
      )}
      <LikeButton track={track} size={18} className="sm" />
      <span className="dur">{track.duration > 0 ? fmtTime(track.duration) : ''}</span>
      <button type="button" className="icon-btn sm more" aria-label="Ещё" title="Ещё" onClick={menu}>
        <Ellipsis size={18} />
      </button>
    </div>
  )
})

interface ListProps {
  tracks: Track[]
  source: Source
  showAlbum?: boolean
  showWhy?: boolean
  head?: boolean
  playlistId?: number
  numbered?: boolean
  numLabel?: (i: number) => ReactNode
  onPlay?: (i: number) => void
  className?: string
  rowClassName?: string
}

export function TrackList({ tracks, source, showAlbum, showWhy, head, playlistId, numLabel, onPlay, className = '', rowClassName }: ListProps) {
  const playTrack = usePlayer((s) => s.playTrack)
  return (
    <div className={`tracks ${className}`}>
      {head && <ListHead showAlbum={showAlbum} />}
      {tracks.map((t, i) => (
        <TrackRow
          key={`${t.id}-${i}`}
          track={t}
          n={i + 1}
          numLabel={numLabel?.(i)}
          showAlbum={showAlbum}
          showWhy={showWhy}
          className={rowClassName}
          ctx={{ playlistId }}
          onPlay={() => (onPlay ? onPlay(i) : playTrack(t, tracks, source))}
        />
      ))}
    </div>
  )
}

function ListHead({ showAlbum }: { showAlbum?: boolean }) {
  return (
    <div className="tracks-head" style={{ ['--cols' as string]: showAlbum ? '32px 48px minmax(0,1.4fr) minmax(0,1fr) 40px 52px 40px' : '32px 48px minmax(0,1fr) 40px 52px 40px' }}>
      <span style={{ textAlign: 'center' }}>#</span>
      <span />
      <span>Название</span>
      {showAlbum && <span className="alb">Альбом</span>}
      <span />
      <span style={{ textAlign: 'right' }}>
        <Clock3 size={14} />
      </span>
      <span />
    </div>
  )
}

function SortableRow({ track, i, onPlay, playlistId }: { track: Track; i: number; onPlay: () => void; playlistId: number }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: track.id })
  return (
    <TrackRow
      track={track}
      n={i + 1}
      showAlbum
      onPlay={onPlay}
      ctx={{ playlistId }}
      drag={{
        attributes,
        listeners,
        setRef: setNodeRef,
        dragging: isDragging,
        style: { transform: CSS.Translate.toString(transform), transition },
      }}
    />
  )
}

/** Список с перетаскиванием — для своих плейлистов. */
export function SortableTrackList({ tracks, source, playlistId, onReorder }: { tracks: Track[]; source: Source; playlistId: number; onReorder: (t: Track[]) => void }) {
  const playTrack = usePlayer((s) => s.playTrack)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const end = (e: DragEndEvent) => {
    if (!e.over || e.active.id === e.over.id) return
    const from = tracks.findIndex((t) => t.id === e.active.id)
    const to = tracks.findIndex((t) => t.id === e.over!.id)
    onReorder(arrayMove(tracks, from, to))
  }
  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={end}>
      <SortableContext items={tracks.map((t) => t.id)} strategy={verticalListSortingStrategy}>
        <div className="tracks">
          <ListHead showAlbum />
          {tracks.map((t, i) => (
            <SortableRow key={t.id} track={t} i={i} playlistId={playlistId} onPlay={() => playTrack(t, tracks, source)} />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  )
}
