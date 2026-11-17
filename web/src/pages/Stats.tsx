import { BarChart3, ChevronRight, Flame, ThumbsDown, UserX } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useSearch } from 'wouter'
import { Cover, Empty, ErrorNote, RowsSkeleton } from '../components/common'
import { TopBar } from '../components/Shell'
import { artistHref, TrackList } from '../components/TrackList'
import { api } from '../lib/api'
import { firstArtist, plural } from '../lib/format'
import { useAsync } from '../lib/hooks'
import { bucketLabel, bucketTitle, groupDigits, minutesLong, persona, WEEKDAYS, wrappedYear } from '../lib/stats'
import type { ArtistStat, Stats, StatsPeriod, Track } from '../lib/types'
import { usePlayer } from '../player/store'
import { useTaste } from '../store/taste'

const PERIODS: { id: StatsPeriod; title: string; prev: string }[] = [
  { id: 'week', title: 'Неделя', prev: 'к прошлой неделе' },
  { id: 'month', title: 'Месяц', prev: 'к прошлому месяцу' },
  { id: 'year', title: 'Год', prev: 'к прошлому году' },
  { id: 'all', title: 'Всё время', prev: '' },
]

/** Число, которое плавно набегает от нуля, — как в приложении. */
function useCountUp(target: number, ms = 900): number {
  const [v, setV] = useState(0)
  const from = useRef(0)
  useEffect(() => {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return setV(target)
    const start = performance.now()
    const a = from.current
    let raf = 0
    const tick = (now: number) => {
      const k = Math.min(1, (now - start) / ms)
      const e = 1 - Math.pow(1 - k, 3)
      const x = a + (target - a) * e
      from.current = x
      setV(x)
      if (k < 1) raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [target, ms])
  return Math.round(v)
}

/** Столбики: высота — доля от максимума, подпись снизу, точное значение в подсказке. */
export function Bars({ values, labels, titles, height = 140, unit = 'мин' }: { values: number[]; labels: string[]; titles?: string[]; height?: number; unit?: string }) {
  const max = Math.max(1, ...values)
  const top = values.indexOf(Math.max(...values))
  return (
    <div className="bars" style={{ ['--bars-h' as string]: `${height}px` } as React.CSSProperties} role="img" aria-label={values.map((v, i) => `${titles?.[i] ?? labels[i]}: ${v} ${unit}`).join(', ')}>
      {values.map((v, i) => (
        <div key={i} className={`bar ${i === top && v > 0 ? 'top' : ''}`} title={`${titles?.[i] ?? labels[i]} — ${v} ${unit}`}>
          <span className="col">
            <i style={{ height: `${v > 0 ? Math.max(4, (v / max) * 100) : 0}%` }} />
          </span>
          <span className="lbl">{labels[i]}</span>
        </div>
      ))}
    </div>
  )
}

function Hero({ s, period }: { s: Stats; period: (typeof PERIODS)[number] }) {
  const n = useCountUp(s.minutes)
  const prev = s.prevMinutes
  const pct = prev && prev > 0 && period.prev ? Math.round(((s.minutes - prev) * 100) / prev) : null
  return (
    <section className="stat-card stat-hero">
      <div className="big num">{groupDigits(n)}</div>
      <div className="h3">{plural(s.minutes, 'минута', 'минуты', 'минут')} музыки</div>
      {pct !== null && (
        <div className={`delta ${pct >= 0 ? 'up' : 'down'}`}>
          {pct >= 0 ? `+${pct}` : `−${-pct}`} % {period.prev}
        </div>
      )}
      <div className="minis">
        <Mini n={s.plays} words={['прослушивание', 'прослушивания', 'прослушиваний']} />
        <Mini n={s.tracks} words={['трек', 'трека', 'треков']} />
        <Mini n={s.artists} words={['исполнитель', 'исполнителя', 'исполнителей']} />
      </div>
      {s.newArtists > 0 && <p className="small" style={{ opacity: 0.75, marginTop: 10 }}>Из них новых для тебя: {s.newArtists}</p>}
    </section>
  )
}

function Mini({ n, words }: { n: number; words: [string, string, string] }) {
  return (
    <div>
      <b className="num">{groupDigits(n)}</b>
      <span>{plural(n, ...words)}</span>
    </div>
  )
}

function TopArtist({ a }: { a: ArtistStat }) {
  return (
    <Link href={artistHref(firstArtist(a.name), a.artistId)} className="stat-card stat-top-artist">
      <Cover src={a.image || a.thumb} size={700} label={a.name} />
      <div className="txt">
        <span className="k">Чаще всего</span>
        <b className="ellipsis">{a.name}</b>
        <span>
          {minutesLong(a.minutes)} · {a.plays} {plural(a.plays, 'прослушивание', 'прослушивания', 'прослушиваний')}
        </span>
      </div>
    </Link>
  )
}

function ArtistLines({ list }: { list: ArtistStat[] }) {
  const max = Math.max(1, ...list.map((a) => a.minutes))
  return (
    <div className="artist-lines">
      {list.map((a, i) => (
        <Link key={a.name} href={artistHref(firstArtist(a.name), a.artistId)} className="artist-line">
          <span className="n num">{i + 1}</span>
          <span className="art">
            <Cover src={a.image || a.thumb} size={160} label={a.name} />
          </span>
          <span className="mid">
            <span className="ellipsis t">{a.name}</span>
            <span className="meter">
              <i style={{ width: `${Math.max(4, (a.minutes / max) * 100)}%` }} />
            </span>
          </span>
          <span className="v small muted">{minutesLong(a.minutes)}</span>
        </Link>
      ))}
    </div>
  )
}

export function WrappedLink({ year = wrappedYear() }: { year?: number }) {
  return (
    <Link href="/wrapped" className="wrapped-banner slim">
      <span>
        <b>Итоги {year}</b>
        <span>Твой год в музыке — можно поделиться</span>
      </span>
      <ChevronRight size={24} />
    </Link>
  )
}

export default function StatsPage() {
  const search = useSearch()
  const [, navigate] = useLocation()
  const period = PERIODS.find((p) => p.id === new URLSearchParams(search).get('p')) ?? PERIODS[0]
  const res = useAsync(`stats:${period.id}`, () => api.stats(period.id), 5 * 60_000)
  const s = res.data
  const playList = usePlayer((st) => st.playList)
  const tracks = useMemo<Track[]>(
    () =>
      (s?.topTracks ?? []).map((t) => {
        const { plays, minutes, ...rest } = t
        void plays
        void minutes
        return rest
      }),
    [s],
  )
  // Сколько раз звучал трек — вместо объяснения под исполнителем.
  const shown = useMemo<Track[]>(() => (s?.topTracks ?? []).map((t, i) => ({ ...tracks[i], why: `${t.plays} ${plural(t.plays, 'раз', 'раза', 'раз')}` })), [s, tracks])
  const p = s ? persona(s.hours) : null
  const source = { kind: 'list' as const, title: 'Статистика', href: '/stats' }

  return (
    <>
      <TopBar title="Статистика" />
      <div className="main-inner">
        <div className="lib-head">
          <h1 className="page-title" style={{ margin: 0 }}>
            Статистика
          </h1>
          <div className="seg" role="tablist" aria-label="Период">
            {PERIODS.map((x) => (
              <button key={x.id} role="tab" aria-selected={x.id === period.id} onClick={() => navigate(x.id === 'week' ? '/stats' : `/stats?p=${x.id}`, { replace: true })}>
                {x.title}
              </button>
            ))}
          </div>
        </div>

        {res.error && !s && <ErrorNote text={res.error} retry={res.reload} />}
        {res.loading && !s && <RowsSkeleton n={6} />}
        {s && !s.plays && (
          <Empty icon={<BarChart3 size={26} />} title="Пока пусто" text="Слушай музыку, и здесь появятся любимые исполнители, треки и минуты.">
            <Link href="/" className="btn btn-light">
              Включить «Мою волну»
            </Link>
          </Empty>
        )}
        {s && s.plays > 0 && (
          <div className="stats">
            <Hero s={s} period={period} />
            {s.topArtists[0] && <TopArtist a={s.topArtists[0]} />}

            <section className="stat-card wide">
              <h2>{s.bucketKind === 'month' ? 'По месяцам' : 'По дням'}</h2>
              <Bars
                values={s.buckets.map((b) => b.minutes)}
                labels={s.buckets.map((b, i) => bucketLabel(b.start, s.bucketKind, s.buckets.length, i))}
                titles={s.buckets.map((b) => bucketTitle(b.start, s.bucketKind))}
              />
            </section>

            {s.topArtists.length > 1 && (
              <section className="stat-card">
                <h2>Исполнители</h2>
                <ArtistLines list={s.topArtists} />
              </section>
            )}
            {tracks.length > 0 && (
              <section className="stat-card">
                <h2>Треки</h2>
                <TrackList className="stat-tracks" tracks={shown} source={source} showWhy onPlay={(i) => playList(tracks, i, source)} />
              </section>
            )}

            {p && (
              <section className="stat-card">
                <h2>{p.title}</h2>
                <p className="muted small">{p.text}</p>
                <Bars values={s.hours} labels={s.hours.map((_, h) => (h % 6 === 0 ? String(h) : ''))} titles={s.hours.map((_, h) => `${h}:00–${h}:59`)} height={110} />
              </section>
            )}
            <section className="stat-card">
              <h2>По дням недели</h2>
              <Bars values={s.weekdays} labels={WEEKDAYS} height={110} />
              <div className="streaks">
                <div>
                  <Flame size={22} />
                  <span>
                    <b>
                      {s.streak} {plural(s.streak, 'день', 'дня', 'дней')}
                    </b>
                    <span>сейчас подряд</span>
                  </span>
                </div>
                <div>
                  <Flame size={22} />
                  <span>
                    <b>
                      {s.bestStreak} {plural(s.bestStreak, 'день', 'дня', 'дней')}
                    </b>
                    <span>рекорд</span>
                  </span>
                </div>
              </div>
            </section>
          </div>
        )}
        <div style={{ marginTop: 18 }}>
          <WrappedLink />
        </div>
      </div>
    </>
  )
}

// ───────── Скрытое ─────────
/** Что не попадает в рекомендации: треки с «Не нравится» и скрытые исполнители. Отсюда можно вернуть. */
export function HiddenPage() {
  // Всё скрытое уже лежит в хранилище: обновляем его и показываем. «Вернуть» убирает строку сразу.
  const tracks = useTaste((s) => s.tracks)
  const artists = useTaste((s) => s.blocked)
  const loaded = useTaste((s) => s.loaded)
  const undislike = useTaste((s) => s.undislike)
  const unblock = useTaste((s) => s.unblock)
  const playList = usePlayer((s) => s.playList)
  useEffect(() => {
    void useTaste.getState().load()
  }, [])
  return (
    <>
      <TopBar title="Скрытое" />
      <div className="main-inner">
        <h1 className="page-title" style={{ marginBottom: 8 }}>
          Скрытое
        </h1>
        <p className="muted" style={{ maxWidth: 640, marginBottom: 24 }}>
          Этого не будет в «Моей волне», радио и миксах. Сами треки и исполнители не пропадают: их можно найти и включить.
        </p>
        {!loaded && <RowsSkeleton n={4} />}
        {loaded && !tracks.length && !artists.length && (
          <Empty icon={<ThumbsDown size={26} />} title="Ничего не скрыто" text="Нажми «Не нравится» или «Не предлагать исполнителя» в меню трека «⋯»." />
        )}
        {artists.length > 0 && (
          <section className="section" style={{ marginTop: 8 }}>
            <div className="section-head">
              <h2>Исполнители</h2>
            </div>
            <div className="hidden-list">
              {artists.map((name) => (
                <div key={name} className="hidden-row">
                  <span className="ico">
                    <UserX size={20} />
                  </span>
                  <Link href={artistHref(name)} className="ellipsis t">
                    {name}
                  </Link>
                  <button className="btn btn-sm btn-ghost" onClick={() => void unblock(name)}>
                    Вернуть
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}
        {tracks.length > 0 && (
          <section className="section">
            <div className="section-head">
              <h2>Не нравится</h2>
            </div>
            <div className="hidden-list">
              {tracks.map((t) => (
                <div key={t.id} className="hidden-row">
                  {/* Скрыт только из рекомендаций: послушать можно. */}
                  <button className="art" onClick={() => playList([t], 0, { kind: 'list', title: 'Скрытое', href: '/hidden' })} aria-label={`Играть «${t.title}»`}>
                    <Cover src={t.thumb} size={120} label={t.title} />
                  </button>
                  <span style={{ display: 'grid', minWidth: 0 }}>
                    <span className="ellipsis t">{t.title}</span>
                    <span className="ellipsis small muted">{t.artist}</span>
                  </span>
                  <button className="btn btn-sm btn-ghost" onClick={() => void undislike(t.id)}>
                    Вернуть
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}
      </div>
    </>
  )
}
