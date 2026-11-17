import { BarChart3, ChevronRight, Heart, Play, RefreshCw, SlidersHorizontal, Sparkles } from 'lucide-react'
import { Link } from 'wouter'
import { CardsSkeleton, Cover, PlayFab, RowsSkeleton } from '../components/common'
import { MediaCard, MixCard, MoodCard, Shelf, TrackCard } from '../components/Cards'
import { TopBar } from '../components/Shell'
import { TrackList } from '../components/TrackList'
import { api } from '../lib/api'
import { getRegion, isDefaultWave, MOODS, regionTitle, waveLabel } from '../lib/data'
import { ago, greeting, plural, tracksWord } from '../lib/format'
import { useAsync } from '../lib/hooks'
import { wrappedSeason } from '../lib/stats'
import type { Track } from '../lib/types'
import { current, usePlayer, type Source } from '../player/store'
import { useAuth } from '../store/auth'
import { useLibrary } from '../store/library'
import { useVisible } from '../store/taste'
import { useUi } from '../store/ui'

function Wave() {
  const wave = usePlayer((s) => s.wave)
  const playing = usePlayer((s) => s.playing)
  const buffering = usePlayer((s) => s.buffering)
  const track = usePlayer((s) => current(s))
  const startWave = usePlayer((s) => s.startWave)
  const settings = usePlayer((s) => s.waveSettings)
  const openDialog = useUi((s) => s.openDialog)
  const active = wave && !!track
  const tuned = !isDefaultWave(settings)
  return (
    <section className={`wave ${active && playing ? 'playing' : ''}`} aria-label="Моя волна">
      <div className="wave-blob" aria-hidden />
      <div>
        <div className="wave-top">
          <h2>Моя волна</h2>
          <button
            className={`wave-tune ${tuned ? 'on' : ''}`}
            onClick={() => openDialog({ kind: 'wave' })}
            title="Настроить волну"
            aria-label={tuned ? `Настроить волну: сейчас ${waveLabel(settings)}` : 'Настроить волну'}
          >
            <SlidersHorizontal size={16} />
            <span>Настроить</span>
          </button>
        </div>
        {tuned && (
          <button className="wave-set" onClick={() => openDialog({ kind: 'wave' })}>
            {waveLabel(settings)}
          </button>
        )}
        <p>Бесконечный поток под твой вкус. Учится на лайках, прослушиваниях и пропусках — чем больше слушаешь, тем точнее.</p>
      </div>
      <div className="wave-row">
        <PlayFab
          playing={active && playing}
          loading={buffering && !playing}
          onClick={() => void startWave()}
          className="xl light"
          size={30}
          label={active && playing ? 'Пауза' : 'Включить «Мою волну»'}
        />
        {active && track ? (
          <div className="wave-now">
            <div className="art">
              <Cover src={track.thumb} size={120} label={track.title} />
            </div>
            <div style={{ minWidth: 0 }}>
              <div className="ellipsis" style={{ fontWeight: 650 }}>
                {track.title}
              </div>
              <div className="ellipsis small" style={{ opacity: 0.75 }}>
                {track.why || track.artist}
              </div>
            </div>
          </div>
        ) : (
          <span style={{ fontWeight: 600, opacity: 0.85 }}>Включить</span>
        )}
      </div>
    </section>
  )
}

/** «Неделя в музыке»: минуты и главный исполнитель недели, вход в статистику. */
function WeekTeaser() {
  const res = useAsync('stats:week', () => api.stats('week'), 10 * 60_000)
  const s = res.data
  if (!s || !s.plays) return null
  const top = s.topArtists[0]
  return (
    <Link href="/stats" className="teaser">
      <span className="teaser-art">{top?.image || top?.thumb ? <Cover src={top.image || top.thumb} size={200} label={top.name} /> : <BarChart3 size={22} />}</span>
      <span style={{ display: 'grid', minWidth: 0 }}>
        <b>Неделя в музыке</b>
        <span className="muted small ellipsis">
          {s.minutes} {plural(s.minutes, 'минута', 'минуты', 'минут')}
          {top ? ` · больше всего ${top.name}` : ''}
        </span>
      </span>
      <ChevronRight size={20} className="faint" />
    </Link>
  )
}

/** «Итоги года» на главной — в декабре и январе. */
function WrappedBanner() {
  const year = wrappedSeason()
  if (!year) return null
  return (
    <Link href="/wrapped" className="wrapped-banner">
      <span>
        <b>Итоги {year}</b>
        <span>Твой год в музыке: главные треки, исполнители и минуты</span>
      </span>
      <Sparkles size={30} />
    </Link>
  )
}

function TrackShelf({ title, tracks, source }: { title: string; tracks: Track[]; source: Source }) {
  if (tracks.length < 2) return null
  return (
    <Shelf title={title}>
      {tracks.map((t, i) => (
        <TrackCard key={`${t.id}-${i}`} track={t} list={tracks} index={i} source={source} />
      ))}
    </Shelf>
  )
}

export default function Home() {
  const me = useAuth((s) => s.me)
  const likes = useLibrary((s) => s.likes)
  const playList = usePlayer((s) => s.playList)
  const region = getRegion()
  const history = useAsync('history:20', () => api.history(20), 30_000)
  const recs = useAsync('recs', () => api.recs(), 10 * 60_000)
  const mixes = useAsync('mixes', () => api.mixes(), 10 * 60_000)
  const top = useAsync(`top:${region}`, () => api.top(region), 30 * 60_000)
  const forYou = useVisible(recs.data)
  const repeat = useVisible(mixes.data?.repeat)
  const forgotten = useVisible(mixes.data?.forgotten)
  const last = history.data?.[0]
  const name = me?.name?.split(' ')[0]

  return (
    <>
      <TopBar title="Главная" />
      <div className="main-inner">
        <div className="home-head">
          <h1>
            {greeting()}
            {name ? `, ${name}` : ''}
          </h1>
        </div>

        <div className="bento">
          <Wave />
          {last ? (
            <button className="tile tile-continue" style={{ textAlign: 'left' }} onClick={() => playList(history.data!, 0, { kind: 'list', title: 'Недавнее', href: '/library?tab=history' })}>
              <div className="art">
                <Cover src={last.thumb} size={300} label={last.title} />
              </div>
              <div style={{ display: 'grid', gap: 4, minWidth: 0 }}>
                <span className="k">Продолжить · {ago(last.playedAt)}</span>
                <span className="big ellipsis">{last.title}</span>
                <span className="muted ellipsis">{last.artist}</span>
              </div>
            </button>
          ) : (
            <Link href="/search" className="tile tile-continue">
              <div className="art" style={{ display: 'grid', placeItems: 'center', background: 'rgb(255 255 255 / .06)' }}>
                <Play size={30} />
              </div>
              <div style={{ display: 'grid', gap: 4 }}>
                <span className="k">С чего начать</span>
                <span className="big">Найди любимого исполнителя</span>
              </div>
            </Link>
          )}
          <Link href="/liked" className="tile tile-liked">
            <span className="k" style={{ color: 'inherit', opacity: 0.85 }}>
              Мне нравится
            </span>
            <span className="big num">{likes.length}</span>
            <span className="small" style={{ opacity: 0.8 }}>
              {likes.length ? tracksWord(likes.length) : 'Нажимай ♥ на треках'}
            </span>
            <Heart className="corner" size={26} fill="currentColor" style={{ opacity: 0.85 }} />
          </Link>
          <Link href="/top" className="tile tile-top">
            <span className="k">Топ недели</span>
            <span className="big">{regionTitle(region)}</span>
            <BarChart3 className="corner" size={24} style={{ color: 'var(--accent)' }} />
          </Link>
        </div>

        <WrappedBanner />

        {mixes.data && mixes.data.mixes.length > 0 && (
          <Shelf title="Миксы дня">
            {mixes.data.mixes.map((m, i) => (
              <MixCard key={m.id} mix={m} index={i} />
            ))}
          </Shelf>
        )}

        {history.data && history.data.length > 2 && (
          <Shelf title="Недавно играло" more={{ href: '/library?tab=history' }}>
            {history.data.slice(0, 16).map((h, i) => (
              <MediaCard
                key={h.id}
                href={h.artistId ? `/artist/${h.artistId}` : `/artist/n/${encodeURIComponent(h.artist.split(',')[0])}`}
                title={h.title}
                sub={h.artist}
                art={<Cover src={h.thumb} size={400} label={h.title} />}
                load={async () => {
                  const list = history.data!
                  return [...list.slice(i), ...list.slice(0, i)]
                }}
                source={{ kind: 'list', title: 'Недавнее', href: '/library?tab=history' }}
              />
            ))}
          </Shelf>
        )}

        <section className="section">
          <div className="section-head">
            <h2>Для тебя</h2>
            <button className="more-link" onClick={recs.reload} style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
              <RefreshCw size={14} /> Обновить
            </button>
          </div>
          {recs.loading && !recs.data ? (
            <RowsSkeleton n={6} />
          ) : forYou.length ? (
            <TrackList className="quick" tracks={forYou.slice(0, 18)} source={{ kind: 'mix', title: 'Для тебя', href: '/' }} numbered={false} numLabel={() => ''} />
          ) : (
            <p className="muted">Послушай и лайкни пару треков — подборка появится здесь.</p>
          )}
        </section>

        <TrackShelf title="На повторе" tracks={repeat} source={{ kind: 'list', title: 'На повторе', href: '/' }} />

        <WeekTeaser />

        <Shelf title="Настроение" more={{ href: '/moods' }} cardW={220}>
          {MOODS.slice(0, 10).map((m) => (
            <MoodCard key={m.id} m={m} />
          ))}
        </Shelf>

        <TrackShelf title="Забытое любимое" tracks={forgotten} source={{ kind: 'list', title: 'Забытое любимое', href: '/' }} />

        <section className="section">
          <div className="section-head">
            <h2>Топ: {regionTitle(region)}</h2>
            <Link className="more-link" href="/top">
              Весь чарт
            </Link>
          </div>
          {top.loading && !top.data ? (
            <CardsSkeleton />
          ) : (
            <TrackList
              className="quick chart"
              rowClassName="chart-row"
              tracks={(top.data ?? []).slice(0, 9)}
              source={{ kind: 'list', title: `Топ: ${regionTitle(region)}`, href: '/top' }}
              onPlay={(i) => playList(top.data!, i, { kind: 'list', title: `Топ: ${regionTitle(region)}`, href: '/top' })}
            />
          )}
        </section>
      </div>
    </>
  )
}
