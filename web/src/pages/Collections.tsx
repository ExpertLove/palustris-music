import { BookmarkPlus, Check, Ellipsis, Heart, Link2, ListEnd, Pencil, Search as SearchIcon, Share2, Shuffle, Trash2 } from 'lucide-react'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams } from 'wouter'
import { AlbumCardView, ArtistCardView, Shelf } from '../components/Cards'
import { Collage, Cover, Empty, ErrorNote, PlayFab, RowsSkeleton } from '../components/common'
import { Dialog, playlistChanged } from '../components/Overlays'
import { TopBar } from '../components/Shell'
import { SortableTrackList, TrackList } from '../components/TrackList'
import { api } from '../lib/api'
import { getRegion, MOODS, REGIONS, regionTitle, setRegion } from '../lib/data'
import { shuffled, totalDuration, tracksWord } from '../lib/format'
import { invalidate, useAsync } from '../lib/hooks'
import type { Track } from '../lib/types'
import { usePlayer, type Source } from '../player/store'
import { useAuth } from '../store/auth'
import { useLibrary } from '../store/library'
import { toast, useUi } from '../store/ui'

/** Кнопка «играть/пауза» для коллекции: если сейчас играет она же — ставит паузу. */
function CollectionPlay({ tracks, source }: { tracks: Track[]; source: Source }) {
  const playing = usePlayer((s) => s.playing && s.source?.href === source.href)
  const buffering = usePlayer((s) => s.buffering && s.source?.href === source.href)
  const same = usePlayer((s) => s.source?.href === source.href && s.index >= 0)
  const playList = usePlayer((s) => s.playList)
  const toggle = usePlayer((s) => s.toggle)
  return (
    <PlayFab
      playing={playing}
      loading={buffering}
      size={26}
      onClick={() => (same ? toggle() : playList(tracks, 0, source))}
      label={playing ? 'Пауза' : 'Играть'}
    />
  )
}

function ShuffleBtn({ tracks, source }: { tracks: Track[]; source: Source }) {
  const playList = usePlayer((s) => s.playList)
  return (
    <button className="icon-btn lg" onClick={() => playList(shuffled(tracks), 0, source)} aria-label="Перемешать и играть" title="Перемешать">
      <Shuffle size={24} />
    </button>
  )
}

function Hero({ art, kind, title, sub, round, color }: { art: ReactNode; kind: string; title: string; sub: ReactNode; round?: boolean; color?: string }) {
  return (
    <div className="hero">
      <div className="hero-bg" style={color ? ({ ['--hero-c' as string]: color } as React.CSSProperties) : undefined} />
      <div className={`hero-art ${round ? 'round' : ''}`}>{art}</div>
      <div className="hero-text">
        <span className="hero-kind">{kind}</span>
        <h1 className={`hero-title ${title.length > 26 ? 'long' : ''}`}>{title}</h1>
        <div className="hero-sub">{sub}</div>
      </div>
    </div>
  )
}

const dur = (t: Track[]) => t.reduce((a, x) => a + (x.duration || 0), 0)

// ───────── Альбом / плейлист YouTube Music ─────────
export function AlbumPage() {
  const { id } = useParams<{ id: string }>()
  const res = useAsync(`col:${id}`, () => api.collection(id), 30 * 60_000)
  const openDialog = useUi((s) => s.openDialog)
  const addToQueue = usePlayer((s) => s.addToQueue)
  const c = res.data
  const source: Source = { kind: 'list', title: c?.title ?? '', href: `/album/${id}` }
  const artistTracks = useAsync(c?.artistId ? `artist:${c.artistId}` : null, () => api.artist({ id: c!.artistId! }), 30 * 60_000)
  const more = artistTracks.data?.albums.filter((a) => a.id !== id) ?? []

  return (
    <>
      <TopBar title={c?.title} />
      <div className="main-inner">
        {res.error && <ErrorNote text={res.error} retry={res.reload} />}
        {res.loading && !c && <RowsSkeleton n={10} />}
        {c && (
          <>
            <Hero
              art={<Cover src={c.thumb} size={600} label={c.title} eager />}
              kind={c.kind === 'single' ? 'Сингл' : c.kind === 'playlist' ? 'Плейлист' : 'Альбом'}
              title={c.title}
              sub={
                <>
                  {c.artistId ? <Link href={`/artist/${c.artistId}`}>{c.artist}</Link> : c.artist && <b>{c.artist}</b>}
                  {c.sub && <span>{c.sub}</span>}
                  {!c.sub && <span>{tracksWord(c.tracks.length)}</span>}
                </>
              }
            />
            <div className="actions">
              <CollectionPlay tracks={c.tracks} source={source} />
              <ShuffleBtn tracks={c.tracks} source={source} />
              <button className="icon-btn lg" title="Сохранить в мои плейлисты" aria-label="Сохранить в мои плейлисты" onClick={() => openDialog({ kind: 'new-playlist', tracks: c.tracks })}>
                <BookmarkPlus size={24} />
              </button>
              <button className="icon-btn lg" title="Добавить всё в очередь" aria-label="Добавить всё в очередь" onClick={() => addToQueue(c.tracks)}>
                <ListEnd size={24} />
              </button>
            </div>
            <TrackList tracks={c.tracks} source={source} head showAlbum={c.kind === 'playlist'} />
            {c.description && <p className="muted small" style={{ maxWidth: 720, marginTop: 32, whiteSpace: 'pre-line' }}>{c.description.slice(0, 600)}</p>}
            {more.length > 0 && (
              <Shelf title={`Ещё у ${c.artist}`} more={{ href: `/artist/${c.artistId}` }}>
                {more.map((a) => (
                  <AlbumCardView key={a.id} a={a} showArtist={false} />
                ))}
              </Shelf>
            )}
          </>
        )}
      </div>
    </>
  )
}

// ───────── Исполнитель ─────────
export function ArtistPage({ byName }: { byName?: boolean }) {
  const params = useParams<{ id?: string; name?: string }>()
  const key = byName ? `artist-n:${params.name}` : `artist:${params.id}`
  const res = useAsync(key, () => api.artist(byName ? { name: decodeURIComponent(params.name!) } : { id: params.id }), 30 * 60_000)
  const [all, setAll] = useState<Track[] | null>(null)
  const [aboutOpen, setAboutOpen] = useState(false)
  const a = res.data
  const [, navigate] = useLocation()
  useEffect(() => {
    // Имя → id: адрес становится постоянным.
    if (byName && a?.id) navigate(`/artist/${a.id}`, { replace: true })
    setAll(null)
  }, [a?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const source: Source = { kind: 'list', title: a?.name ?? '', href: `/artist/${a?.id}` }
  const loadAll = async () => {
    if (!a?.allTracks) return
    try {
      const c = await api.collection(a.allTracks)
      setAll(c.tracks)
    } catch (e) {
      toast((e as Error).message)
    }
  }

  return (
    <>
      <TopBar title={a?.name} />
      {res.error && (
        <div className="main-inner">
          <ErrorNote text={res.error} retry={res.reload} />
        </div>
      )}
      {res.loading && !a && (
        <div className="main-inner">
          <div className="sk" style={{ height: 300, borderRadius: 24, marginBottom: 24 }} />
          <RowsSkeleton n={5} />
        </div>
      )}
      {a && (
        <div className="main-inner">
          <div className="artist-hero">
            <div className="bg">{a.thumb && <img src={a.thumb} alt="" crossOrigin="anonymous" />}</div>
            <div>
              <h1>{a.name}</h1>
              {a.listeners && <div className="listeners">{a.listeners}</div>}
            </div>
          </div>
          <div className="actions" style={{ marginTop: 18 }}>
            <CollectionPlay tracks={all ?? a.tracks} source={source} />
            <ShuffleBtn tracks={all ?? a.tracks} source={source} />
          </div>
          <section>
            <div className="section-head">
              <h2>Популярное</h2>
              {a.allTracks && !all && (
                <button className="more-link" onClick={loadAll}>
                  Все треки
                </button>
              )}
            </div>
            <TrackList tracks={all ?? a.tracks} source={source} showAlbum />
          </section>
          {a.albums.length > 0 && (
            <Shelf title="Альбомы">
              {a.albums.map((x) => (
                <AlbumCardView key={x.id} a={x} showArtist={false} />
              ))}
            </Shelf>
          )}
          {a.singles.length > 0 && (
            <Shelf title="Синглы и EP">
              {a.singles.map((x) => (
                <AlbumCardView key={x.id} a={x} showArtist={false} />
              ))}
            </Shelf>
          )}
          {a.playlists.length > 0 && (
            <Shelf title="Плейлисты">
              {a.playlists.map((x) => (
                <AlbumCardView key={x.id} a={x} />
              ))}
            </Shelf>
          )}
          {a.similar.length > 0 && (
            <Shelf title="Похожие исполнители">
              {a.similar.map((x) => (
                <ArtistCardView key={x.id} a={x} />
              ))}
            </Shelf>
          )}
          {a.description && (
            <section className="section" style={{ maxWidth: 760 }}>
              <div className="section-head">
                <h2>Об исполнителе</h2>
              </div>
              <p className={`muted ${aboutOpen ? '' : 'clamp-2'}`} style={{ whiteSpace: 'pre-line', WebkitLineClamp: aboutOpen ? 'unset' : 4 }}>
                {a.description}
              </p>
              {a.description.length > 280 && (
                <button className="link" style={{ marginTop: 10 }} onClick={() => setAboutOpen(!aboutOpen)}>
                  {aboutOpen ? 'Свернуть' : 'Читать полностью'}
                </button>
              )}
            </section>
          )}
        </div>
      )}
    </>
  )
}

// ───────── Мне нравится ─────────
export function LikedPage() {
  const likes = useLibrary((s) => s.likes)
  const loaded = useLibrary((s) => s.loaded)
  const me = useAuth((s) => s.me)
  const [filter, setFilter] = useState('')
  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase()
    return f ? likes.filter((t) => `${t.title} ${t.artist} ${t.album ?? ''}`.toLowerCase().includes(f)) : likes
  }, [likes, filter])
  const source: Source = { kind: 'list', title: 'Мне нравится', href: '/liked' }
  return (
    <>
      <TopBar title="Мне нравится" />
      <div className="main-inner">
        <Hero
          art={
            <span className="liked-art">
              <Heart size={84} fill="currentColor" />
            </span>
          }
          kind="Плейлист"
          title="Мне нравится"
          sub={
            <>
              <b>{me?.name || 'Ты'}</b>
              <span>{tracksWord(likes.length)}</span>
              {dur(likes) > 0 && <span>{totalDuration(dur(likes))}</span>}
            </>
          }
          color="var(--accent)"
        />
        {likes.length > 0 && (
          <div className="actions">
            <CollectionPlay tracks={likes} source={source} />
            <ShuffleBtn tracks={likes} source={source} />
            <span style={{ flex: 1 }} />
            <div className="search-box" style={{ maxWidth: 280 }}>
              <SearchIcon size={18} />
              <input type="search" placeholder="Найти в любимых" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Найти в любимых" style={{ height: 42 }} />
            </div>
          </div>
        )}
        {!loaded && <RowsSkeleton />}
        {loaded && !likes.length && (
          <Empty icon={<Heart size={26} />} title="Здесь будут любимые треки" text="Нажимай на сердечко у трека — он сохранится сюда и поможет «Моей волне» понять твой вкус.">
            <Link href="/search" className="btn btn-light">
              Найти музыку
            </Link>
          </Empty>
        )}
        {shown.length > 0 && <TrackList tracks={shown} source={source} head showAlbum />}
        {likes.length > 0 && !shown.length && <p className="muted">Ничего не нашлось.</p>}
      </div>
    </>
  )
}

// ───────── Свой плейлист ─────────
export function PlaylistPage() {
  const params = useParams<{ id: string }>()
  const id = Number(params.id)
  const res = useAsync(`playlist:${id}`, () => api.playlist(id), 60_000)
  const [, navigate] = useLocation()
  const loadPlaylists = useLibrary((s) => s.loadPlaylists)
  const [menu, setMenu] = useState(false)
  const [rename, setRename] = useState(false)
  const [confirmDel, setConfirmDel] = useState(false)
  const [share, setShare] = useState(false)
  const p = res.data

  useEffect(() => {
    const on = (e: Event) => (e as CustomEvent).detail === id && res.reload()
    window.addEventListener('playlist-changed', on)
    return () => window.removeEventListener('playlist-changed', on)
  }, [id, res.reload])

  const source: Source = { kind: 'list', title: p?.name ?? '', href: `/playlist/${id}` }
  const thumbs = useMemo(() => [...new Set((p?.items ?? []).map((t) => t.thumb).filter(Boolean) as string[])].slice(0, 4), [p])

  const reorder = async (items: Track[]) => {
    if (!p) return
    res.setData({ ...p, items })
    try {
      await api.reorderPlaylist(id, items.map((t) => t.id))
      loadPlaylists()
    } catch (e) {
      toast((e as Error).message)
      res.reload()
    }
  }

  return (
    <>
      <TopBar title={p?.name} />
      <div className="main-inner">
        {res.error && <ErrorNote text={res.error} retry={res.reload} />}
        {res.loading && !p && <RowsSkeleton />}
        {p && (
          <>
            <Hero
              art={<Collage thumbs={thumbs} label={p.name} />}
              kind={p.share ? 'Плейлист · открыт по ссылке' : 'Плейлист'}
              title={p.name}
              sub={
                <>
                  <span>{tracksWord(p.items.length)}</span>
                  {dur(p.items) > 0 && <span>{totalDuration(dur(p.items))}</span>}
                </>
              }
            />
            <div className="actions">
              {p.items.length > 0 && (
                <>
                  <CollectionPlay tracks={p.items} source={source} />
                  <ShuffleBtn tracks={p.items} source={source} />
                </>
              )}
              <button className="icon-btn lg" onClick={() => setShare(true)} aria-label="Поделиться" title="Поделиться">
                <Share2 size={22} />
              </button>
              <div style={{ position: 'relative' }}>
                <button className="icon-btn lg" onClick={() => setMenu(!menu)} aria-label="Ещё" aria-expanded={menu}>
                  <Ellipsis size={24} />
                </button>
                {menu && (
                  <div className="menu" style={{ position: 'absolute', top: 52, left: 0 }} onMouseLeave={() => setMenu(false)}>
                    <button className="menu-item" onClick={() => (setMenu(false), setRename(true))}>
                      <Pencil size={18} /> Переименовать
                    </button>
                    <button className="menu-item danger" onClick={() => (setMenu(false), setConfirmDel(true))}>
                      <Trash2 size={18} /> Удалить плейлист
                    </button>
                  </div>
                )}
              </div>
            </div>
            {p.items.length ? (
              <>
                <SortableTrackList tracks={p.items} source={source} playlistId={id} onReorder={reorder} />
                <p className="small faint" style={{ marginTop: 14 }}>
                  Порядок меняется перетаскиванием за ⋮⋮ слева.
                </p>
              </>
            ) : (
              <Empty icon={<ListEnd size={26} />} title="Плейлист пуст" text="Открой меню «⋯» у любого трека и выбери «Добавить в плейлист».">
                <Link href="/search" className="btn btn-light">
                  Найти треки
                </Link>
              </Empty>
            )}
          </>
        )}
      </div>
      {rename && p && (
        <RenameDialog
          name={p.name}
          onClose={() => setRename(false)}
          onSave={async (name) => {
            await api.renamePlaylist(id, name)
            res.setData({ ...p, name })
            loadPlaylists()
            setRename(false)
            toast('Плейлист переименован')
          }}
        />
      )}
      {confirmDel && p && (
        <Dialog title="Удалить плейлист?" onClose={() => setConfirmDel(false)} width={420}>
          <p className="muted">«{p.name}» исчезнет вместе со списком треков. Сами треки останутся в каталоге.</p>
          <div className="dialog-actions">
            <button className="btn btn-ghost" onClick={() => setConfirmDel(false)}>
              Отмена
            </button>
            <button
              className="btn btn-danger"
              onClick={async () => {
                try {
                  await api.deletePlaylist(id)
                  invalidate(`playlist:${id}`)
                  await loadPlaylists()
                  toast('Плейлист удалён')
                  navigate('/library')
                } catch (e) {
                  toast((e as Error).message)
                }
              }}
            >
              Удалить
            </button>
          </div>
        </Dialog>
      )}
      {share && p && (
        <ShareDialog
          slug={p.share}
          onClose={() => setShare(false)}
          onShare={async () => {
            const { share: s } = await api.sharePlaylist(id)
            res.setData({ ...p, share: s })
            loadPlaylists()
            playlistChanged(id)
            return s
          }}
          onUnshare={async () => {
            await api.unsharePlaylist(id)
            res.setData({ ...p, share: null })
            loadPlaylists()
          }}
        />
      )}
    </>
  )
}

function RenameDialog({ name, onClose, onSave }: { name: string; onClose: () => void; onSave: (n: string) => Promise<void> }) {
  const [v, setV] = useState(name)
  const [err, setErr] = useState('')
  return (
    <Dialog title="Переименовать" onClose={onClose}>
      <form
        style={{ display: 'grid', gap: 14, marginTop: 14 }}
        onSubmit={async (e) => {
          e.preventDefault()
          try {
            await onSave(v.trim())
          } catch (x) {
            setErr((x as Error).message)
          }
        }}
      >
        <input className="input" autoFocus value={v} maxLength={100} onChange={(e) => setV(e.target.value)} />
        {err && <p className="form-error">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Отмена
          </button>
          <button className="btn btn-primary" disabled={!v.trim()}>
            Сохранить
          </button>
        </div>
      </form>
    </Dialog>
  )
}

function ShareDialog({ slug, onClose, onShare, onUnshare }: { slug: string | null; onClose: () => void; onShare: () => Promise<string>; onUnshare: () => Promise<void> }) {
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const url = slug ? `${location.origin}${import.meta.env.BASE_URL}s/${slug}` : ''
  const copy = async (u: string) => {
    try {
      await navigator.clipboard.writeText(u)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1800)
    } catch {
      /* noop */
    }
  }
  return (
    <Dialog title="Поделиться плейлистом" onClose={onClose}>
      {slug ? (
        <>
          <p className="muted" style={{ marginBottom: 16 }}>
            Плейлист открыт по ссылке: его увидит любой, у кого она есть. Слушать смогут те, у кого есть аккаунт.
          </p>
          <div style={{ display: 'flex', gap: 8 }}>
            <input className="input" readOnly value={url} onFocus={(e) => e.target.select()} />
            <button className="btn btn-primary" onClick={() => copy(url)} style={{ height: 50 }}>
              {copied ? <Check size={18} /> : <Link2 size={18} />}
              {copied ? 'Готово' : 'Копировать'}
            </button>
          </div>
          <div className="dialog-actions" style={{ justifyContent: 'space-between' }}>
            <button
              className="btn btn-danger"
              disabled={busy}
              onClick={async () => {
                setBusy(true)
                await onUnshare().catch((e) => toast(e.message))
                setBusy(false)
              }}
            >
              Закрыть доступ
            </button>
            <button className="btn btn-ghost" onClick={onClose}>
              Готово
            </button>
          </div>
        </>
      ) : (
        <>
          <p className="muted" style={{ marginBottom: 10 }}>
            Создам ссылку, по которой плейлист можно открыть без входа. Доступ можно закрыть в любой момент.
          </p>
          <div className="dialog-actions">
            <button className="btn btn-ghost" onClick={onClose}>
              Отмена
            </button>
            <button
              className="btn btn-primary"
              disabled={busy}
              onClick={async () => {
                setBusy(true)
                try {
                  const s = await onShare()
                  await copy(`${location.origin}${import.meta.env.BASE_URL}s/${s}`)
                  toast('Ссылка создана и скопирована')
                } catch (e) {
                  toast((e as Error).message)
                }
                setBusy(false)
              }}
            >
              <Link2 size={18} /> Создать ссылку
            </button>
          </div>
        </>
      )}
    </Dialog>
  )
}

// ───────── Плейлист по ссылке ─────────
export function SharedPage() {
  const { slug } = useParams<{ slug: string }>()
  const res = useAsync(`shared:${slug}`, () => api.shared(slug), 60_000)
  const status = useAuth((s) => s.status)
  const openDialog = useUi((s) => s.openDialog)
  const p = res.data
  const thumbs = useMemo(() => [...new Set((p?.items ?? []).map((t) => t.thumb).filter(Boolean) as string[])].slice(0, 4), [p])
  const source: Source = { kind: 'list', title: p?.name ?? '', href: `/s/${slug}` }
  const guest = status !== 'user'
  return (
    <>
      {!guest && <TopBar title={p?.name} />}
      <div className="main-inner" style={guest ? { paddingTop: 32 } : undefined}>
        {res.error && <ErrorNote text="Плейлист не найден или доступ по ссылке закрыт." />}
        {res.loading && !p && <RowsSkeleton />}
        {p && (
          <>
            <Hero
              art={<Collage thumbs={thumbs} label={p.name} />}
              kind="Плейлист"
              title={p.name}
              sub={
                <>
                  {p.owner && <b>{p.owner}</b>}
                  <span>{tracksWord(p.items.length)}</span>
                  {dur(p.items) > 0 && <span>{totalDuration(dur(p.items))}</span>}
                </>
              }
            />
            <div className="actions">
              {guest ? (
                <>
                  <Link href="/register" className="btn btn-primary btn-lg">
                    Создать аккаунт и слушать
                  </Link>
                  <Link href="/login" className="btn btn-outline btn-lg">
                    Войти
                  </Link>
                </>
              ) : (
                <>
                  <CollectionPlay tracks={p.items} source={source} />
                  <ShuffleBtn tracks={p.items} source={source} />
                  <button className="icon-btn lg" title="Сохранить к себе" aria-label="Сохранить к себе" onClick={() => openDialog({ kind: 'new-playlist', tracks: p.items })}>
                    <BookmarkPlus size={24} />
                  </button>
                </>
              )}
            </div>
            {guest ? (
              <div className="tracks">
                {p.items.map((t, i) => (
                  <div key={t.id} className="trow no-album" style={{ cursor: 'default' }}>
                    <span className="n">{i + 1}</span>
                    <div className="art">
                      <Cover src={t.thumb} size={120} label={t.title} />
                    </div>
                    <div className="meta">
                      <span className="t ellipsis">{t.title}</span>
                      <span className="s ellipsis">{t.artist}</span>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <TrackList tracks={p.items} source={source} head showAlbum />
            )}
          </>
        )}
      </div>
    </>
  )
}

// ───────── Настроение ─────────
export function MoodPage() {
  const { id } = useParams<{ id: string }>()
  const m = MOODS.find((x) => x.id === id)
  const res = useAsync(m ? `mood:${m.id}` : null, () => api.searchTracks(m!.q), 30 * 60_000)
  if (!m)
    return (
      <div className="main-inner">
        <Empty icon={<SearchIcon size={26} />} title="Нет такого настроения" />
      </div>
    )
  const Icon = m.icon
  const source: Source = { kind: 'list', title: m.title, href: `/mood/${m.id}` }
  const tracks = res.data ?? []
  return (
    <>
      <TopBar title={m.title} />
      <div className="main-inner">
        <Hero
          art={
            <div className="mood" style={{ ['--from' as string]: m.from, ['--to' as string]: m.to, width: '100%', height: '100%', aspectRatio: 'auto', placeItems: 'center', alignContent: 'center' } as React.CSSProperties}>
              <Icon size={96} strokeWidth={1.4} />
            </div>
          }
          kind="Настроение"
          title={m.title}
          sub={<span>{m.sub[0].toUpperCase() + m.sub.slice(1)}</span>}
          color={m.from}
        />
        {tracks.length > 0 && (
          <div className="actions">
            <CollectionPlay tracks={tracks} source={source} />
            <ShuffleBtn tracks={tracks} source={source} />
          </div>
        )}
        {res.loading && !res.data && <RowsSkeleton />}
        {res.error && <ErrorNote text={res.error} retry={res.reload} />}
        {tracks.length > 0 && <TrackList tracks={tracks} source={source} showAlbum />}
      </div>
    </>
  )
}

export function MoodsPage() {
  return (
    <>
      <TopBar title="Настроения" />
      <div className="main-inner">
        <h1 className="page-title">Настроения и жанры</h1>
        <div className="moods">
          {MOODS.map((m) => {
            const Icon = m.icon
            return (
              <Link key={m.id} href={`/mood/${m.id}`} className="mood" style={{ ['--from' as string]: m.from, ['--to' as string]: m.to } as React.CSSProperties}>
                <Icon className="ico" size={120} strokeWidth={1.4} aria-hidden />
                <b>{m.title}</b>
                <span>{m.sub}</span>
              </Link>
            )
          })}
        </div>
      </div>
    </>
  )
}

// ───────── Топ ─────────
export function TopPage() {
  const [region, setR] = useState(getRegion)
  const res = useAsync(`top:${region}`, () => api.top(region), 30 * 60_000)
  const source: Source = { kind: 'list', title: `Топ: ${regionTitle(region)}`, href: '/top' }
  const tracks = res.data ?? []
  return (
    <>
      <TopBar title="Топ недели" />
      <div className="main-inner">
        <div className="lib-head">
          <div>
            <h1 className="page-title" style={{ margin: 0 }}>
              Топ недели
            </h1>
            <p className="muted">Самые популярные треки YouTube за неделю.</p>
          </div>
          <label className="field" style={{ minWidth: 220 }}>
            <span>Страна</span>
            <select
              className="input"
              value={region}
              onChange={(e) => {
                setRegion(e.target.value)
                setR(e.target.value)
              }}
            >
              {REGIONS.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.title}
                </option>
              ))}
            </select>
          </label>
        </div>
        {tracks.length > 0 && (
          <div className="actions">
            <CollectionPlay tracks={tracks} source={source} />
            <ShuffleBtn tracks={tracks} source={source} />
          </div>
        )}
        {res.loading && !res.data && <RowsSkeleton n={12} />}
        {res.error && <ErrorNote text={res.error} retry={res.reload} />}
        {tracks.length > 0 && <TrackList tracks={tracks} source={source} rowClassName="chart-row" />}
      </div>
    </>
  )
}
