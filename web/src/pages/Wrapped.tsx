import { ChevronLeft, ChevronRight, Download, Share2, X } from 'lucide-react'
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useLocation } from 'wouter'
import { Cover } from '../components/common'
import { api } from '../lib/api'
import { bigThumb, isVideoThumb, plural } from '../lib/format'
import { useAsync, useMobile } from '../lib/hooks'
import { dayTitle, groupDigits, minutesLong, MONTHS_FULL, MONTHS_SHORT, persona, wrappedYear } from '../lib/stats'
import type { Wrapped } from '../lib/types'
import { toast } from '../store/ui'

// «Итоги года» — сторис, как в приложении: карточки листаются сами, нажатие справа — дальше, слева — назад,
// удержание — пауза. Любую карточку можно отправить картинкой: она рисуется на canvas заново, в 1080×1920.

type Kind = 'intro' | 'minutes' | 'topArtist' | 'artists' | 'topTrack' | 'tracks' | 'months' | 'time' | 'facts' | 'summary'

const COLORS: Record<Kind, [string, string, string]> = {
  intro: ['#ff4d8d', '#7b47e8', '#16123a'],
  minutes: ['#1db9a0', '#1c5bd8', '#0b1630'],
  topArtist: ['#ff8a3d', '#d8336f', '#2a0f24'],
  artists: ['#7b47e8', '#2aa2e0', '#0e1433'],
  topTrack: ['#f0c33c', '#e0612f', '#2b1a0e'],
  tracks: ['#2c9a52', '#139184', '#0b2219'],
  months: ['#5150d8', '#b8285f', '#15122e'],
  time: ['#3b3a9e', '#8c3fd6', '#0c0b22'],
  facts: ['#e8562b', '#7b47e8', '#1a0f2e'],
  summary: ['#ff4d8d', '#7b47e8', '#1c1b4b'],
}

const STORY_MS = 7000

function storiesOf(w: Wrapped): Kind[] {
  const s: Kind[] = ['intro', 'minutes']
  if (w.topArtists[0]) s.push('topArtist')
  if (w.topArtists.length >= 3) s.push('artists')
  if (w.topTracks[0]) s.push('topTrack')
  if (w.topTracks.length >= 3) s.push('tracks')
  if (w.months.filter((m) => m > 0).length >= 2) s.push('months')
  if (persona(w.hours)) s.push('time')
  s.push('facts', 'summary')
  return s
}

const minutesWord = (n: number) => plural(n, 'минута', 'минуты', 'минут')

/** Строки «А ещё»: число и подпись. */
function facts(w: Wrapped): [string, string][] {
  const f: [string, string][] = [[groupDigits(w.tracks), plural(w.tracks, 'разный трек', 'разных трека', 'разных треков')]]
  if (w.newArtists > 0) f.push([groupDigits(w.newArtists), plural(w.newArtists, 'новый исполнитель', 'новых исполнителя', 'новых исполнителей')])
  if (w.bestStreak > 1) f.push([String(w.bestStreak), `${plural(w.bestStreak, 'день', 'дня', 'дней')} подряд с музыкой`])
  if (w.busiestDay) f.push([minutesLong(w.busiestDay.minutes), `в самый музыкальный день — ${dayTitle(w.busiestDay.start)}`])
  if (w.likes > 0) f.push([groupDigits(w.likes), plural(w.likes, 'трек в «Мне нравится»', 'трека в «Мне нравится»', 'треков в «Мне нравится»')])
  return f
}

function hoursLine(min: number): string | null {
  const h = Math.floor(min / 60)
  if (h < 2) return null
  return `Это ${h} ${plural(h, 'час', 'часа', 'часов')}` + (h >= 48 ? `, или ${Math.floor(h / 24)} ${plural(Math.floor(h / 24), 'сутки', 'суток', 'суток')} без перерыва` : '')
}

// ───────────── экран ─────────────

export default function WrappedPage() {
  const year = wrappedYear()
  const res = useAsync(`wrapped:${year}`, () => api.wrapped(year), 10 * 60_000)
  const [, navigate] = useLocation()
  const close = () => (history.length > 1 ? history.back() : navigate('/'))
  const w = res.data
  // Поверх всего, включая плеер и вкладки: иначе они перекрывают карточки и кнопку «Поделиться».
  return createPortal(
    <div className="wr" role="dialog" aria-modal="true" aria-label={`Итоги ${year}`}>
      {!w && !res.error && <span className="wr-spin" aria-label="Загружаю итоги" />}
      {(res.error || (w && w.plays < 5)) && (
        <div className="wr-empty">
          <h2>Итоги пока не готовы</h2>
          <p>{res.error ?? 'Слушай музыку — к концу года здесь будет твоя история.'}</p>
          <button className="btn btn-light" onClick={close}>
            Понятно
          </button>
        </div>
      )}
      {w && w.plays >= 5 && <Stories w={w} onClose={close} />}
      <button className="icon-btn wr-close" onClick={close} aria-label="Закрыть">
        <X size={24} />
      </button>
    </div>,
    document.body,
  )
}

function Stories({ w, onClose }: { w: Wrapped; onClose: () => void }) {
  const list = useMemo(() => storiesOf(w), [w])
  const [i, setI] = useState(0)
  const [hold, setHold] = useState(false)
  const [sharing, setSharing] = useState(false)
  const [hidden, setHidden] = useState(() => document.hidden)
  const mobile = useMobile()
  const down = useRef<{ t: number; x: number } | null>(null)
  const reduced = useMemo(() => matchMedia('(prefers-reduced-motion: reduce)').matches, [])
  // Окно «Поделиться» и свёрнутая вкладка тоже ставят на паузу: вернувшись, человек увидит ту же карточку.
  const paused = hold || sharing || hidden
  const kind = list[i]

  const go = (to: number) => {
    if (to >= list.length) return onClose()
    setI(Math.max(0, to))
  }
  const goRef = useRef(go)
  useLayoutEffect(() => {
    goRef.current = go
  })

  useEffect(() => {
    const v = () => setHidden(document.hidden)
    document.addEventListener('visibilitychange', v)
    return () => document.removeEventListener('visibilitychange', v)
  }, [])

  useEffect(() => {
    // В захвате, чтобы стрелки и пробел не перематывали трек под сторис.
    const k = (e: KeyboardEvent) => {
      const key = e.key
      if (key !== 'ArrowRight' && key !== 'ArrowLeft' && key !== 'Escape' && e.code !== 'Space') return
      e.stopPropagation()
      e.preventDefault()
      if (key === 'Escape') onClose()
      else if (key === 'ArrowLeft') goRef.current(i - 1)
      else goRef.current(i + 1)
    }
    window.addEventListener('keydown', k, true)
    return () => window.removeEventListener('keydown', k, true)
  }, [i, onClose])

  const share = async () => {
    setSharing(true)
    try {
      const blob = await renderStory(kind, w)
      const name = `itogi-${w.year}-${i + 1}.png`
      const file = new File([blob], name, { type: 'image/png' })
      const top = w.topArtists[0]
      const text = `Мои итоги ${w.year} в Музыке: ${groupDigits(w.minutes)} ${minutesWord(w.minutes)}${top ? `, исполнитель года — ${top.name}` : ''}. music.palustris.ru`
      if (mobile && navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], text })
      } else {
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = name
        a.click()
        window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
        toast('Картинка сохранена — её можно отправить друзьям')
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') toast('Не получилось подготовить картинку')
    } finally {
      setSharing(false)
    }
  }

  const [c1, c2, c3] = COLORS[kind]
  return (
    <div className="wr-stage">
      <button className="icon-btn lg wr-nav" onClick={() => go(i - 1)} disabled={i === 0} aria-label="Предыдущая карточка">
        <ChevronLeft size={28} />
      </button>
      <div
        className="wr-card"
        style={{ ['--c1' as string]: c1, ['--c2' as string]: c2, ['--c3' as string]: c3 } as React.CSSProperties}
        onPointerDown={(e) => {
          down.current = { t: Date.now(), x: e.clientX }
          setHold(true)
        }}
        onPointerUp={(e) => {
          setHold(false)
          const d = down.current
          down.current = null
          // Короткое нажатие листает, долгое — только пауза.
          if (!d || Date.now() - d.t > 260) return
          const r = e.currentTarget.getBoundingClientRect()
          go(e.clientX - r.left < r.width / 3 ? i - 1 : i + 1)
        }}
        onPointerLeave={() => setHold(false)}
        onPointerCancel={() => setHold(false)}
      >
        <div className="wr-bg" aria-hidden>
          <i />
          <i />
          <i />
        </div>
        <div className="wr-bars" aria-hidden>
          {list.map((_, k) => (
            <span key={k}>
              <i
                key={k === i ? `run-${i}` : `s-${k}`}
                className={k < i ? 'done' : k === i ? (reduced ? 'done' : 'run') : ''}
                style={{ animationDuration: `${STORY_MS}ms`, animationPlayState: paused ? 'paused' : 'running' }}
                onAnimationEnd={() => k === i && i < list.length - 1 && goRef.current(i + 1)}
              />
            </span>
          ))}
        </div>
        <div className="wr-body" key={i} aria-live="polite">
          <StoryView kind={kind} w={w} />
        </div>
        <span className="wr-foot">Музыка · music.palustris.ru</span>
        <button
          className="btn btn-light btn-sm wr-share"
          disabled={sharing}
          onPointerDown={(e) => e.stopPropagation()}
          onPointerUp={(e) => e.stopPropagation()}
          onClick={share}
        >
          {mobile ? <Share2 size={16} /> : <Download size={16} />}
          {mobile ? 'Поделиться' : 'Скачать картинку'}
        </button>
      </div>
      <button className="icon-btn lg wr-nav" onClick={() => go(i + 1)} aria-label={i === list.length - 1 ? 'Закрыть итоги' : 'Следующая карточка'}>
        <ChevronRight size={28} />
      </button>
    </div>
  )
}

function StoryView({ kind, w }: { kind: Kind; w: Wrapped }) {
  switch (kind) {
    case 'intro':
      return (
        <div className="wr-c">
          <div className="wr-year">{w.year}</div>
          <h2 className="wr-h">
            Твой год
            <br />в музыке
          </h2>
          <p className="wr-p">Нажимай справа, чтобы листать дальше, и удерживай, чтобы остановить.</p>
        </div>
      )
    case 'minutes': {
      const hl = hoursLine(w.minutes)
      return (
        <div className="wr-c">
          <p className="wr-k">Музыки за {w.year} год</p>
          <CountUp n={w.minutes} className="wr-num" />
          <h2 className="wr-h sm">{minutesWord(w.minutes)}</h2>
          {hl && <p className="wr-p">{hl}</p>}
          {w.percentile !== null && w.percentile >= 30 && <p className="wr-p">Больше, чем у {w.percentile} % слушателей</p>}
        </div>
      )
    }
    case 'topArtist': {
      const a = w.topArtists[0]
      return (
        <div className="wr-c center">
          <p className="wr-k">Исполнитель года</p>
          <div className="wr-img round big">
            <Cover src={a.image || a.thumb} size={600} label={a.name} eager />
          </div>
          <h2 className="wr-h">{a.name}</h2>
          <p className="wr-p">
            {minutesLong(a.minutes)} · {a.plays} {plural(a.plays, 'прослушивание', 'прослушивания', 'прослушиваний')}
          </p>
        </div>
      )
    }
    case 'artists':
      return (
        <div className="wr-c">
          <h2 className="wr-h sm">Топ исполнителей</h2>
          <ol className="wr-list">
            {w.topArtists.slice(0, 5).map((a, k) => (
              <li key={a.name} style={{ animationDelay: `${k * 90}ms` }}>
                <b>{k + 1}</b>
                <span className="wr-img round">
                  <Cover src={a.image || a.thumb} size={200} label={a.name} eager />
                </span>
                <span className="txt">
                  <span className="ellipsis t">{a.name}</span>
                  <span className="s">{minutesLong(a.minutes)}</span>
                </span>
              </li>
            ))}
          </ol>
        </div>
      )
    case 'topTrack': {
      const t = w.topTracks[0]
      return (
        <div className="wr-c center">
          <p className="wr-k">Песня года</p>
          <div className="wr-img big">
            <Cover src={t.thumb} size={600} label={t.title} eager />
          </div>
          <h2 className="wr-h sm clamp-2">{t.title}</h2>
          <p className="wr-p">{t.artist}</p>
          <p className="wr-p">
            Звучала {t.plays} {plural(t.plays, 'раз', 'раза', 'раз')}
          </p>
        </div>
      )
    }
    case 'tracks':
      return (
        <div className="wr-c">
          <h2 className="wr-h sm">Топ треков</h2>
          <ol className="wr-list">
            {w.topTracks.slice(0, 5).map((t, k) => (
              <li key={t.id} style={{ animationDelay: `${k * 90}ms` }}>
                <b>{k + 1}</b>
                <span className="wr-img">
                  <Cover src={t.thumb} size={200} label={t.title} eager />
                </span>
                <span className="txt">
                  <span className="ellipsis t">{t.title}</span>
                  <span className="ellipsis s">
                    {t.artist} · {t.plays} {plural(t.plays, 'раз', 'раза', 'раз')}
                  </span>
                </span>
              </li>
            ))}
          </ol>
        </div>
      )
    case 'months': {
      const best = w.months.indexOf(Math.max(...w.months))
      return (
        <div className="wr-c">
          <p className="wr-k">Самый музыкальный месяц</p>
          <h2 className="wr-num sm">{MONTHS_FULL[best][0].toUpperCase() + MONTHS_FULL[best].slice(1)}</h2>
          <p className="wr-p">{minutesLong(w.months[best])}</p>
          <WrBars values={w.months} labels={MONTHS_SHORT.map((m) => m[0].toUpperCase())} />
        </div>
      )
    }
    case 'time': {
      const p = persona(w.hours)!
      return (
        <div className="wr-c">
          <p className="wr-k">Ты —</p>
          <h2 className="wr-num sm">{p.title.toLowerCase()}</h2>
          <p className="wr-p">{p.text}</p>
          <WrBars values={w.hours} labels={w.hours.map((_, h) => (h % 6 === 0 ? String(h) : ''))} />
        </div>
      )
    }
    case 'facts':
      return (
        <div className="wr-c">
          <h2 className="wr-h sm">А ещё</h2>
          <div className="wr-facts">
            {facts(w).map(([v, t], k) => (
              <div key={t} style={{ animationDelay: `${k * 110}ms` }}>
                <b>{v}</b>
                <span>{t}</span>
              </div>
            ))}
          </div>
        </div>
      )
    case 'summary': {
      const a = w.topArtists[0]
      return (
        <div className="wr-c">
          <div className="wr-sum">
            <div className="head">
              <span className="wr-img">{a ? <Cover src={a.image || a.thumb} size={300} label={a.name} eager /> : null}</span>
              <span>
                <b>Итоги {w.year}</b>
                <span>
                  {groupDigits(w.minutes)} {minutesWord(w.minutes)}
                </span>
              </span>
            </div>
            <div className="cols">
              <SumCol title="Исполнители" items={w.topArtists.map((x) => x.name)} />
              <SumCol title="Треки" items={w.topTracks.map((x) => x.title)} />
            </div>
          </div>
        </div>
      )
    }
  }
}

function SumCol({ title, items }: { title: string; items: string[] }) {
  return (
    <div>
      <span className="k">{title}</span>
      {items.slice(0, 5).map((s, k) => (
        <span key={k} className="ellipsis">
          {k + 1}. {s}
        </span>
      ))}
    </div>
  )
}

function WrBars({ values, labels }: { values: number[]; labels: string[] }) {
  const max = Math.max(1, ...values)
  return (
    <div className="wr-bars-chart" aria-hidden>
      {values.map((v, k) => (
        <div key={k} className={v === max ? 'top' : ''}>
          <span>
            <i style={{ height: `${v > 0 ? Math.max(3, (v / max) * 100) : 0}%`, animationDelay: `${k * 30}ms` }} />
          </span>
          <em>{labels[k]}</em>
        </div>
      ))}
    </div>
  )
}

function CountUp({ n, className }: { n: number; className?: string }) {
  const [v, setV] = useState(0)
  useEffect(() => {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return setV(n)
    const start = performance.now()
    let raf = 0
    const tick = (now: number) => {
      const k = Math.min(1, (now - start) / 1600)
      setV(n * (1 - Math.pow(1 - k, 3)))
      if (k < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [n])
  return <div className={className}>{groupDigits(v)}</div>
}

// ───────────── картинка для «Поделиться» ─────────────

const W = 1080
const H = 1920
const PAD = 96
const FONT = '"Onest Variable", Onest, system-ui, sans-serif'
const white = (a = 1) => `rgba(255,255,255,${a})`

const font = (g: CanvasRenderingContext2D, size: number, weight = 700) => {
  g.font = `${weight} ${size}px ${FONT}`
}

/** Строка с многоточием, если не влезает. */
function fit(g: CanvasRenderingContext2D, s: string, max: number): string {
  if (g.measureText(s).width <= max) return s
  let t = s
  while (t.length > 1 && g.measureText(t + '…').width > max) t = t.slice(0, -1)
  return t.trimEnd() + '…'
}

/** Перенос по словам, не больше `lines` строк. */
function wrap(g: CanvasRenderingContext2D, s: string, max: number, lines: number): string[] {
  const words = s.split(/\s+/)
  const out: string[] = []
  let cur = ''
  for (const w of words) {
    const t = cur ? `${cur} ${w}` : w
    if (g.measureText(t).width <= max || !cur) cur = t
    else {
      out.push(cur)
      cur = w
    }
  }
  if (cur) out.push(cur)
  if (out.length > lines) {
    const rest = out.slice(lines - 1).join(' ')
    return [...out.slice(0, lines - 1), fit(g, rest, max)]
  }
  return out.map((l) => fit(g, l, max))
}

function text(g: CanvasRenderingContext2D, s: string, x: number, y: number, size: number, weight = 700, color = white(), align: CanvasTextAlign = 'left', max = W - PAD * 2) {
  font(g, size, weight)
  g.fillStyle = color
  g.textAlign = align
  g.textBaseline = 'alphabetic'
  g.fillText(fit(g, s, max), x, y)
}

function loadImage(url: string | null | undefined, px = 600): Promise<HTMLImageElement | null> {
  const src = bigThumb(url, px)
  if (!src) return Promise.resolve(null)
  return new Promise((resolve) => {
    const img = new Image()
    // С CORS: иначе холст «испортится» и картинку не получится сохранить.
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = () => resolve(null)
    img.src = src
  })
}

function drawImage(g: CanvasRenderingContext2D, img: HTMLImageElement | null, x: number, y: number, w: number, h: number, r: number, round = false) {
  g.save()
  g.beginPath()
  if (round) g.arc(x + w / 2, y + h / 2, w / 2, 0, Math.PI * 2)
  else g.roundRect(x, y, w, h, r)
  g.clip()
  g.fillStyle = white(0.12)
  g.fillRect(x, y, w, h)
  if (img) {
    // Кадр клипа 4:3 с чёрными полосами — обрезаем, как на сайте.
    const zoom = isVideoThumb(img.src) ? 1.36 : 1
    const scale = Math.max(w / img.naturalWidth, h / img.naturalHeight) * zoom
    const sw = w / scale
    const sh = h / scale
    g.drawImage(img, (img.naturalWidth - sw) / 2, (img.naturalHeight - sh) / 2, sw, sh, x, y, w, h)
  }
  g.restore()
}

function drawBars(g: CanvasRenderingContext2D, values: number[], labels: string[], x: number, y: number, w: number, h: number) {
  const max = Math.max(1, ...values)
  const step = w / values.length
  const bw = step * 0.62
  values.forEach((v, k) => {
    const bh = v > 0 ? Math.max(8, (v / max) * h) : 4
    g.fillStyle = v === Math.max(...values) ? white() : white(0.4)
    g.beginPath()
    g.roundRect(x + k * step + (step - bw) / 2, y + h - bh, bw, bh, Math.min(14, bw / 2))
    g.fill()
    if (labels[k]) text(g, labels[k], x + k * step + step / 2, y + h + 56, 34, 600, white(0.7), 'center', step * 3)
  })
}

async function renderStory(kind: Kind, w: Wrapped): Promise<Blob> {
  await Promise.all([document.fonts?.load(`900 100px ${FONT}`), document.fonts?.load(`500 40px ${FONT}`)]).catch(() => null)
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')!
  const [c1, c2, c3] = COLORS[kind]
  g.fillStyle = c3
  g.fillRect(0, 0, W, H)
  for (const [cx, cy, r, col] of [
    [W * 0.15, H * 0.12, W * 1.0, c1],
    [W * 0.95, H * 0.5, W * 0.95, c2],
    [W * 0.25, H * 0.95, W * 0.85, c1],
  ] as [number, number, number, string][]) {
    const grad = g.createRadialGradient(cx, cy, 0, cx, cy, r)
    grad.addColorStop(0, col + 'd9')
    grad.addColorStop(1, col + '00')
    g.fillStyle = grad
    g.fillRect(0, 0, W, H)
  }
  const inner = W - PAD * 2

  switch (kind) {
    case 'intro':
      text(g, String(w.year), PAD, 860, 300, 900)
      text(g, 'Мой год', PAD, 1010, 120, 800)
      text(g, 'в музыке', PAD, 1140, 120, 800)
      break
    case 'minutes': {
      text(g, `Музыки за ${w.year} год`, PAD, 640, 58, 600, white(0.85))
      text(g, groupDigits(w.minutes), PAD, 900, 250, 900)
      text(g, minutesWord(w.minutes), PAD, 1010, 80, 700)
      let y = 1140
      const hl = hoursLine(w.minutes)
      if (hl) {
        font(g, 50, 500)
        for (const l of wrap(g, hl, inner, 2)) {
          text(g, l, PAD, y, 50, 500, white(0.85))
          y += 66
        }
      }
      if (w.percentile !== null && w.percentile >= 30) text(g, `Больше, чем у ${w.percentile} % слушателей`, PAD, y + 20, 50, 500, white(0.85))
      break
    }
    case 'topArtist': {
      const a = w.topArtists[0]
      text(g, 'Исполнитель года', W / 2, 420, 60, 600, white(0.85), 'center')
      drawImage(g, await loadImage(a.image || a.thumb), (W - 620) / 2, 500, 620, 620, 0, true)
      font(g, 110, 900)
      let y = 1290
      for (const l of wrap(g, a.name, inner, 2)) {
        text(g, l, W / 2, y, 110, 900, white(), 'center')
        y += 120
      }
      text(g, `${minutesLong(a.minutes)} · ${a.plays} ${plural(a.plays, 'прослушивание', 'прослушивания', 'прослушиваний')}`, W / 2, y + 10, 48, 500, white(0.85), 'center')
      break
    }
    case 'artists':
    case 'tracks': {
      const isA = kind === 'artists'
      text(g, isA ? 'Топ исполнителей' : 'Топ треков', PAD, 520, 96, 800)
      const rows = isA ? w.topArtists.slice(0, 5) : w.topTracks.slice(0, 5)
      const imgs = await Promise.all(rows.map((r) => loadImage(isA ? (r as Wrapped['topArtists'][number]).image || r.thumb : r.thumb, 300)))
      rows.forEach((r, k) => {
        const y = 640 + k * 200
        text(g, String(k + 1), PAD, y + 92, 64, 800)
        drawImage(g, imgs[k], PAD + 80, y, 140, 140, 28, isA)
        const name = isA ? (r as Wrapped['topArtists'][number]).name : (r as Wrapped['topTracks'][number]).title
        const sub = isA ? minutesLong(r.minutes) : `${(r as Wrapped['topTracks'][number]).artist} · ${r.plays} ${plural(r.plays, 'раз', 'раза', 'раз')}`
        text(g, name, PAD + 260, y + 62, 58, 700, white(), 'left', inner - 260)
        text(g, sub, PAD + 260, y + 120, 42, 500, white(0.75), 'left', inner - 260)
      })
      break
    }
    case 'topTrack': {
      const t = w.topTracks[0]
      text(g, 'Песня года', W / 2, 380, 60, 600, white(0.85), 'center')
      drawImage(g, await loadImage(t.thumb), (W - 680) / 2, 460, 680, 680, 56)
      font(g, 92, 900)
      let y = 1290
      for (const l of wrap(g, t.title, inner, 2)) {
        text(g, l, W / 2, y, 92, 900, white(), 'center')
        y += 104
      }
      text(g, t.artist, W / 2, y + 4, 54, 600, white(0.85), 'center')
      text(g, `Звучала ${t.plays} ${plural(t.plays, 'раз', 'раза', 'раз')}`, W / 2, y + 84, 48, 500, white(0.85), 'center')
      break
    }
    case 'months': {
      const best = w.months.indexOf(Math.max(...w.months))
      text(g, 'Самый музыкальный месяц', PAD, 560, 58, 600, white(0.85))
      text(g, MONTHS_FULL[best][0].toUpperCase() + MONTHS_FULL[best].slice(1), PAD, 760, 170, 900)
      text(g, minutesLong(w.months[best]), PAD, 860, 56, 500, white(0.85))
      drawBars(g, w.months, MONTHS_SHORT.map((m) => m[0].toUpperCase()), PAD, 1000, inner, 480)
      break
    }
    case 'time': {
      const p = persona(w.hours)!
      text(g, 'По времени прослушивания', PAD, 520, 58, 600, white(0.85))
      font(g, 150, 900)
      let y = 700
      for (const l of wrap(g, p.title, inner, 2)) {
        text(g, l, PAD, y, 150, 900)
        y += 160
      }
      text(g, p.text, PAD, y, 52, 500, white(0.85))
      drawBars(g, w.hours, w.hours.map((_, h) => (h % 6 === 0 ? String(h) : '')), PAD, y + 120, inner, 420)
      break
    }
    case 'facts': {
      text(g, `${w.year}: а ещё`, PAD, 440, 100, 800)
      facts(w).forEach(([v, t], k) => {
        const y = 640 + k * 230
        text(g, v, PAD, y, 120, 900)
        text(g, t, PAD, y + 70, 46, 500, white(0.85))
      })
      break
    }
    case 'summary': {
      const a = w.topArtists[0]
      g.fillStyle = 'rgba(0,0,0,.35)'
      g.beginPath()
      g.roundRect(PAD - 24, 440, inner + 48, 1000, 64)
      g.fill()
      drawImage(g, await loadImage(a?.image || a?.thumb), PAD + 24, 488, 240, 240, 44)
      text(g, `Итоги ${w.year}`, PAD + 300, 600, 92, 900, white(), 'left', inner - 300)
      text(g, `${groupDigits(w.minutes)} ${minutesWord(w.minutes)}`, PAD + 300, 680, 52, 600, white(0.85), 'left', inner - 300)
      const colW = (inner - 48) / 2
      ;[
        ['Исполнители', w.topArtists.map((x) => x.name)],
        ['Треки', w.topTracks.map((x) => x.title)],
      ].forEach(([title, items], col) => {
        const x = PAD + 24 + col * (colW + 24)
        text(g, title as string, x, 860, 42, 600, white(0.7), 'left', colW)
        ;(items as string[]).slice(0, 5).forEach((s, k) => text(g, `${k + 1}. ${s}`, x, 950 + k * 92, 48, 600, white(), 'left', colW - 10))
      })
      break
    }
  }
  text(g, 'Музыка · music.palustris.ru', PAD, H - 110, 38, 600, white(0.7))
  return new Promise((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error('toBlob'))), 'image/png'))
}
