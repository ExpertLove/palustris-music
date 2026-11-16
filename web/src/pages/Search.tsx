import { Clock3, Search as SearchIcon, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useSearch } from 'wouter'
import { AlbumCardView, ArtistCardView, MoodCard, Shelf } from '../components/Cards'
import { CardsSkeleton, Cover, Empty, ErrorNote, PlayFab, RowsSkeleton } from '../components/common'
import { TopBar } from '../components/Shell'
import { TrackList } from '../components/TrackList'
import { api } from '../lib/api'
import { MOODS } from '../lib/data'
import { useAsync, useDebounced, useMobile } from '../lib/hooks'
import { usePlayer } from '../player/store'
import { toast } from '../store/ui'

const RECENT_KEY = 'music.recent-searches'
const getRecent = (): string[] => {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]')
  } catch {
    return []
  }
}
const pushRecent = (q: string) => {
  const list = [q, ...getRecent().filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, 10)
  localStorage.setItem(RECENT_KEY, JSON.stringify(list))
}

export function SearchBox({ value, onChange, autoFocus }: { value: string; onChange: (v: string) => void; autoFocus?: boolean }) {
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (autoFocus && window.matchMedia('(hover: hover)').matches) ref.current?.focus()
  }, [autoFocus])
  return (
    <form className="search-box" role="search" onSubmit={(e) => (e.preventDefault(), ref.current?.blur())}>
      <SearchIcon size={20} />
      <input
        ref={ref}
        type="search"
        enterKeyHint="search"
        placeholder="Трек, исполнитель, альбом"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label="Поиск"
        autoComplete="off"
        spellCheck={false}
      />
      {value && (
        <button type="button" className="icon-btn sm clear" onClick={() => (onChange(''), ref.current?.focus())} aria-label="Очистить">
          <X size={18} />
        </button>
      )}
    </form>
  )
}

export default function Search() {
  const search = useSearch()
  const [, navigate] = useLocation()
  const initial = new URLSearchParams(search).get('q') ?? ''
  const [q, setQ] = useState(initial)
  const dq = useDebounced(q.trim(), 380)
  const mobile = useMobile()
  const [recent, setRecent] = useState(getRecent)
  const playList = usePlayer((s) => s.playList)
  const res = useAsync(dq ? `search:${dq.toLowerCase()}` : null, () => api.searchAll(dq), 10 * 60_000)

  useEffect(() => {
    const url = dq ? `/search?q=${encodeURIComponent(dq)}` : '/search'
    navigate(url, { replace: true })
    if (dq.length > 1) {
      pushRecent(dq)
      setRecent(getRecent())
    }
  }, [dq]) // eslint-disable-line react-hooks/exhaustive-deps

  const d = res.data
  const nothing = d && !d.top && !d.tracks.length && !d.artists.length && !d.albums.length

  return (
    <>
      <TopBar>{!mobile && <SearchBox value={q} onChange={setQ} autoFocus />}</TopBar>
      <div className="main-inner">
        {mobile && (
          <div style={{ padding: '6px 0 18px' }}>
            <h1 className="page-title" style={{ marginTop: 0 }}>
              Поиск
            </h1>
            <SearchBox value={q} onChange={setQ} />
          </div>
        )}

        {!dq && (
          <>
            {recent.length > 0 && (
              <section style={{ marginBottom: 32 }}>
                <div className="section-head">
                  <h2>Ты искал</h2>
                  <button className="more-link" onClick={() => (localStorage.removeItem(RECENT_KEY), setRecent([]))}>
                    Очистить
                  </button>
                </div>
                <div className="recent">
                  {recent.map((r) => (
                    <button key={r} className="chip" onClick={() => setQ(r)}>
                      <Clock3 size={15} /> {r}
                    </button>
                  ))}
                </div>
              </section>
            )}
            <div className="section-head">
              <h2>Настроения и жанры</h2>
            </div>
            <div className="moods">
              {MOODS.map((m) => (
                <MoodCard key={m.id} m={m} />
              ))}
            </div>
          </>
        )}

        {dq && res.loading && !d && (
          <>
            <RowsSkeleton n={5} />
            <CardsSkeleton round />
          </>
        )}
        {dq && res.error && <ErrorNote text={res.error} retry={res.reload} />}
        {dq && nothing && <Empty icon={<SearchIcon size={26} />} title="Ничего не нашлось" text={`По запросу «${dq}» пусто. Проверь написание или попробуй на другом языке.`} />}

        {d && !nothing && (
          <>
            <div className="search-top">
              {d.top ? (
                <section>
                  <div className="section-head">
                    <h2>Лучший результат</h2>
                  </div>
                  <Link href={`/artist/${d.top.id}`} className="top-card">
                    <div className="art">
                      <Cover src={d.top.thumb} size={300} label={d.top.name} />
                    </div>
                    <div>
                      <div className="name ellipsis">{d.top.name}</div>
                      <div className="muted small">Исполнитель{d.top.sub ? ` · ${d.top.sub}` : ''}</div>
                    </div>
                    <PlayFab
                      playing={false}
                      onClick={async (e) => {
                        e.preventDefault()
                        try {
                          const a = await api.artist({ id: d.top!.id })
                          playList(a.tracks, 0, { kind: 'list', title: a.name, href: `/artist/${a.id}` })
                        } catch (err) {
                          toast((err as Error).message)
                        }
                      }}
                      label={`Играть ${d.top.name}`}
                    />
                  </Link>
                </section>
              ) : null}
              {d.tracks.length > 0 && (
                <section style={!d.top ? { gridColumn: '1 / -1' } : undefined}>
                  <div className="section-head">
                    <h2>Треки</h2>
                  </div>
                  <TrackList tracks={d.tracks.slice(0, d.top ? 4 : 10)} source={{ kind: 'list', title: `Поиск: ${dq}`, href: `/search?q=${encodeURIComponent(dq)}` }} />
                </section>
              )}
            </div>
            {d.tracks.length > 4 && d.top && (
              <section className="section">
                <div className="section-head">
                  <h2>Ещё треки</h2>
                </div>
                <TrackList tracks={d.tracks.slice(4)} source={{ kind: 'list', title: `Поиск: ${dq}`, href: `/search?q=${encodeURIComponent(dq)}` }} numLabel={(i) => i + 5} />
              </section>
            )}
            {d.artists.length > 0 && (
              <Shelf title="Исполнители">
                {d.artists.map((a) => (
                  <ArtistCardView key={a.id} a={a} />
                ))}
              </Shelf>
            )}
            {d.albums.length > 0 && (
              <Shelf title="Альбомы">
                {d.albums.map((a) => (
                  <AlbumCardView key={a.id} a={a} />
                ))}
              </Shelf>
            )}
            {d.playlists.length > 0 && (
              <Shelf title="Плейлисты">
                {d.playlists.map((a) => (
                  <AlbumCardView key={a.id} a={a} />
                ))}
              </Shelf>
            )}
          </>
        )}
      </div>
    </>
  )
}
