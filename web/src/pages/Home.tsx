import { BarChart3, Heart, Play, RefreshCw } from 'lucide-react'
import { Link } from 'wouter'
import { CardsSkeleton, Cover, PlayFab, RowsSkeleton } from '../components/common'
import { MediaCard, MoodCard, Shelf } from '../components/Cards'
import { TopBar } from '../components/Shell'
import { TrackList } from '../components/TrackList'
import { api } from '../lib/api'
import { getRegion, MOODS, regionTitle } from '../lib/data'
import { ago, greeting, tracksWord } from '../lib/format'
import { useAsync } from '../lib/hooks'
import { current, usePlayer } from '../player/store'
import { useAuth } from '../store/auth'
import { useLibrary } from '../store/library'

function Wave() {
  const wave = usePlayer((s) => s.wave)
  const playing = usePlayer((s) => s.playing)
  const buffering = usePlayer((s) => s.buffering)
  const track = usePlayer((s) => current(s))
  const startWave = usePlayer((s) => s.startWave)
  const active = wave && !!track
  return (
    <section className={`wave ${active && playing ? 'playing' : ''}`} aria-label="Моя волна">
      <div className="wave-blob" aria-hidden />
      <div>
        <h2>Моя волна</h2>
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

export default function Home() {
  const me = useAuth((s) => s.me)
  const likes = useLibrary((s) => s.likes)
  const playList = usePlayer((s) => s.playList)
  const region = getRegion()
  const history = useAsync('history:20', () => api.history(20), 30_000)
  const recs = useAsync('recs', () => api.recs(), 10 * 60_000)
  const top = useAsync(`top:${region}`, () => api.top(region), 30 * 60_000)
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
          ) : recs.data && recs.data.length ? (
            <TrackList className="quick" tracks={recs.data.slice(0, 18)} source={{ kind: 'list', title: 'Для тебя', href: '/' }} numbered={false} numLabel={() => ''} />
          ) : (
            <p className="muted">Послушай и лайкни пару треков — подборка появится здесь.</p>
          )}
        </section>

        <Shelf title="Настроение" more={{ href: '/moods' }} cardW={220}>
          {MOODS.slice(0, 10).map((m) => (
            <MoodCard key={m.id} m={m} />
          ))}
        </Shelf>

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
