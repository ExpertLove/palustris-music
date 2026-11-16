import { DndContext, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core'
import { SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { FileText, ListMusic, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../lib/api'
import { activeLine, parseLrc } from '../lib/lrc'
import type { Lyrics as LyricsT, Track } from '../lib/types'
import { current, usePlayer } from '../player/store'
import { useUi } from '../store/ui'
import { Cover, Empty, EqBars } from './common'

const lyricsCache = new Map<string, LyricsT>()

export function Lyrics() {
  const track = usePlayer((s) => current(s))
  const duration = usePlayer((s) => s.duration)
  const time = usePlayer((s) => s.time)
  const seek = usePlayer((s) => s.seek)
  const [data, setData] = useState<LyricsT | null>(null)
  const [loading, setLoading] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const userScroll = useRef(0)

  useEffect(() => {
    if (!track) return
    const key = track.id
    const c = lyricsCache.get(key)
    if (c) return setData(c)
    setData(null)
    setLoading(true)
    let alive = true
    // Ждём длительность из метаданных — LRCLIB точнее находит по ней.
    const t = window.setTimeout(() => {
      api
        .lyrics(track, usePlayer.getState().duration)
        .then((l) => {
          lyricsCache.set(key, l)
          if (alive) setData(l)
        })
        .catch(() => alive && setData({ synced: null, plain: null, instrumental: false, found: false }))
        .finally(() => alive && setLoading(false))
    }, 400)
    return () => {
      alive = false
      window.clearTimeout(t)
    }
  }, [track?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const lines = useMemo(() => (data?.synced ? parseLrc(data.synced) : []), [data])
  const now = lines.length ? activeLine(lines, time) : -1

  useEffect(() => {
    if (now < 0 || Date.now() - userScroll.current < 4000) return
    const el = box.current?.querySelector<HTMLElement>(`[data-i="${now}"]`)
    const sc = box.current?.closest('.panel-body, .np-side .panel-body') as HTMLElement | null
    if (el && sc) sc.scrollTo({ top: el.offsetTop - sc.clientHeight * 0.38, behavior: 'smooth' })
  }, [now])

  if (!track) return <Empty icon={<FileText size={26} />} title="Ничего не играет" text="Включи трек — здесь появится текст." />
  if (loading && !data)
    return (
      <div className="lyrics" aria-busy>
        {[70, 55, 80, 40, 65].map((w, i) => (
          <div key={i} className="sk" style={{ height: 24, width: `${w}%`, margin: '8px 8px' }} />
        ))}
      </div>
    )
  if (data?.instrumental) return <Empty icon={<FileText size={26} />} title="Без слов" text="Это инструментальный трек." />
  if (lines.length)
    return (
      <>
        <div className="lyrics" ref={box} onWheel={() => (userScroll.current = Date.now())} onTouchMove={() => (userScroll.current = Date.now())}>
          {lines.map((l, i) => (
            <button key={i} data-i={i} className={`line ${i === now ? 'now' : i < now ? 'past' : ''} ${l.text ? '' : 'gap'}`} onClick={() => seek(l.t)}>
              {l.text || '♪'}
            </button>
          ))}
        </div>
        <p className="lyrics-src">Текст: LRCLIB · нажми на строку, чтобы перемотать</p>
      </>
    )
  if (data?.plain) return <div className="lyrics plain">{data.plain}</div>
  void duration
  return <Empty icon={<FileText size={26} />} title="Текста пока нет" text="Для этого трека текст не нашёлся. Новые тексты появляются в базе постоянно." />
}

function QRow({ t, i, isCurrent, playing }: { t: Track; i: number; isCurrent: boolean; playing: boolean }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: `${t.id}:${i}` })
  const jumpTo = usePlayer((s) => s.jumpTo)
  const removeAt = usePlayer((s) => s.removeAt)
  const openMenu = useUi((s) => s.openMenu)
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={`q-row ${isCurrent ? 'current' : ''} ${isDragging ? 'dragging' : ''}`}
      onClick={() => !isCurrent && jumpTo(i)}
      onContextMenu={(e) => {
        e.preventDefault()
        openMenu({ track: t, x: e.clientX, y: e.clientY, ctx: { queueIndex: isCurrent ? undefined : i } })
      }}
      {...attributes}
      {...(isCurrent ? {} : listeners)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && !isCurrent && jumpTo(i)}
    >
      <div className="art">
        <Cover src={t.thumb} size={120} label={t.title} />
      </div>
      <div style={{ display: 'grid', minWidth: 0 }}>
        <span className="t ellipsis">{t.title}</span>
        <span className="a ellipsis">{t.artist}</span>
      </div>
      {isCurrent ? (
        <span style={{ padding: '0 10px' }}>
          <EqBars paused={!playing} />
        </span>
      ) : (
        <button
          className="icon-btn sm x"
          aria-label="Убрать из очереди"
          onPointerDown={(e) => e.stopPropagation()}
          onClick={(e) => {
            e.stopPropagation()
            removeAt(i)
          }}
        >
          <X size={16} />
        </button>
      )}
    </div>
  )
}

export function Queue() {
  const queue = usePlayer((s) => s.queue)
  const index = usePlayer((s) => s.index)
  const queued = usePlayer((s) => s.queued)
  const playing = usePlayer((s) => s.playing)
  const source = usePlayer((s) => s.source)
  const wave = usePlayer((s) => s.wave)
  const move = usePlayer((s) => s.move)
  const clearUpcoming = usePlayer((s) => s.clearUpcoming)
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }))

  if (index < 0 || !queue.length) return <Empty icon={<ListMusic size={26} />} title="Очередь пуста" text="Включи альбом, плейлист или «Мою волну»." />

  const upcoming = queue.slice(index + 1, index + 1 + 120)
  const userPart = upcoming.slice(0, queued)
  const rest = upcoming.slice(queued)
  const ids = [index, ...upcoming.map((_, k) => index + 1 + k)].map((i) => `${queue[i].id}:${i}`)
  const end = (e: DragEndEvent) => {
    if (!e.over || e.active.id === e.over.id) return
    const from = Number(String(e.active.id).split(':').pop())
    const to = Number(String(e.over.id).split(':').pop())
    if (to <= index) return
    move(from, to)
  }

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={end}>
      <SortableContext items={ids} strategy={verticalListSortingStrategy}>
        <div className="q-label">Сейчас играет</div>
        <QRow t={queue[index]} i={index} isCurrent playing={playing} />
        {userPart.length > 0 && <div className="q-label">Ты добавил</div>}
        {userPart.map((t, k) => (
          <QRow key={`${t.id}:${index + 1 + k}`} t={t} i={index + 1 + k} isCurrent={false} playing={false} />
        ))}
        {rest.length > 0 && (
          <div className="q-label">
            <span className="ellipsis">Далее{source?.title ? `: ${source.title}` : ''}</span>
            <button className="btn btn-sm btn-ghost" style={{ height: 28 }} onClick={clearUpcoming}>
              Очистить
            </button>
          </div>
        )}
        {rest.map((t, k) => (
          <QRow key={`${t.id}:${index + 1 + queued + k}`} t={t} i={index + 1 + queued + k} isCurrent={false} playing={false} />
        ))}
        {wave && <p className="small faint" style={{ padding: '14px 12px' }}>Волна бесконечная: новые треки подберутся сами.</p>}
      </SortableContext>
    </DndContext>
  )
}
