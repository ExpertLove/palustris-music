import { BarChart3, FileDown, Heart, History, MicVocal, Plus, Trash2 } from 'lucide-react'
import { Link, useLocation, useSearch } from 'wouter'
import { MediaCard } from '../components/Cards'
import { Collage, Cover, Empty, RowsSkeleton } from '../components/common'
import { TopBar } from '../components/Shell'
import { TrackList } from '../components/TrackList'
import { api } from '../lib/api'
import { ago, tracksWord } from '../lib/format'
import { useAsync } from '../lib/hooks'
import { useLibrary } from '../store/library'
import { toast, useUi } from '../store/ui'

type Tab = 'playlists' | 'artists' | 'history'

export default function Library() {
  const search = useSearch()
  const [, navigate] = useLocation()
  const tab = (new URLSearchParams(search).get('tab') as Tab) || 'playlists'
  const setTab = (t: Tab) => navigate(t === 'playlists' ? '/library' : `/library?tab=${t}`, { replace: true })
  return (
    <>
      <TopBar title="Медиатека" />
      <div className="main-inner">
        <div className="lib-head">
          <h1 className="page-title" style={{ margin: 0 }}>
            Медиатека
          </h1>
          <span style={{ flex: 1 }} />
          <Link href="/stats" className="btn btn-ghost btn-sm">
            <BarChart3 size={16} /> Статистика
          </Link>
          <div className="seg" role="tablist">
            <button role="tab" aria-selected={tab === 'playlists'} onClick={() => setTab('playlists')}>
              Плейлисты
            </button>
            <button role="tab" aria-selected={tab === 'artists'} onClick={() => setTab('artists')}>
              Исполнители
            </button>
            <button role="tab" aria-selected={tab === 'history'} onClick={() => setTab('history')}>
              История
            </button>
          </div>
        </div>
        {tab === 'playlists' && <Playlists />}
        {tab === 'artists' && <Artists />}
        {tab === 'history' && <HistoryTab />}
      </div>
    </>
  )
}

function Playlists() {
  const playlists = useLibrary((s) => s.playlists)
  const likes = useLibrary((s) => s.likes)
  const openDialog = useUi((s) => s.openDialog)
  return (
    <div className="grid-cards">
      <MediaCard
        href="/liked"
        title="Мне нравится"
        sub={tracksWord(likes.length)}
        art={
          <span className="liked-art">
            <Heart size={54} fill="currentColor" />
          </span>
        }
        load={likes.length ? async () => likes : undefined}
        source={{ kind: 'list', title: 'Мне нравится', href: '/liked' }}
      />
      {playlists.map((p) => (
        <MediaCard
          key={p.id}
          href={`/playlist/${p.id}`}
          title={p.name}
          sub={`${tracksWord(p.count)}${p.share ? ' · по ссылке' : ''}`}
          art={<Collage thumbs={p.thumbs} label={p.name} />}
          load={p.count ? () => api.playlist(p.id).then((x) => x.items) : undefined}
          source={{ kind: 'list', title: p.name, href: `/playlist/${p.id}` }}
        />
      ))}
      <button className="card" onClick={() => openDialog({ kind: 'new-playlist' })} style={{ textAlign: 'left' }}>
        <div className="card-art" style={{ display: 'grid', placeItems: 'center', background: 'rgb(255 255 255 / .05)', border: '1.5px dashed var(--line-2)', boxShadow: 'none' }}>
          <Plus size={36} />
        </div>
        <span className="t">Новый плейлист</span>
      </button>
      <button className="card" onClick={() => openDialog({ kind: 'import-playlist' })} style={{ textAlign: 'left' }}>
        <div className="card-art" style={{ display: 'grid', placeItems: 'center', background: 'rgb(255 255 255 / .05)', border: '1.5px dashed var(--line-2)', boxShadow: 'none' }}>
          <FileDown size={36} />
        </div>
        <span className="t">Импорт плейлиста</span>
      </button>
    </div>
  )
}

function Artists() {
  const res = useAsync('lib-artists', () => api.artists(), 60_000)
  if (res.loading && !res.data) return <RowsSkeleton n={6} />
  if (!res.data?.length)
    return (
      <Empty icon={<MicVocal size={26} />} title="Пока пусто" text="Здесь соберутся исполнители, которых ты слушаешь и лайкаешь.">
        <Link href="/search" className="btn btn-light">
          Найти музыку
        </Link>
      </Empty>
    )
  return (
    <div className="grid-cards">
      {res.data.map((a) => (
        <MediaCard
          key={a.name}
          round
          href={`/artist/n/${encodeURIComponent(a.name.split(',')[0].trim())}`}
          title={a.name}
          sub={`${a.count} ${a.count === 1 ? 'прослушивание' : a.count < 5 ? 'прослушивания' : 'прослушиваний'}`}
          art={<Cover src={a.thumb} size={300} label={a.name} />}
        />
      ))}
    </div>
  )
}

function HistoryTab() {
  const res = useAsync('history:200', () => api.history(200), 15_000)
  const items = res.data ?? []
  if (res.loading && !res.data) return <RowsSkeleton />
  if (!items.length) return <Empty icon={<History size={26} />} title="История пуста" text="Трек попадает сюда, если дослушать его хотя бы до 30-й секунды." />
  return (
    <>
      <TrackList
        tracks={items}
        source={{ kind: 'list', title: 'История', href: '/library?tab=history' }}
        showAlbum
        numLabel={(i) => <span className="tiny">{ago(items[i].playedAt).replace(' назад', '')}</span>}
      />
      <div style={{ marginTop: 24 }}>
        <button
          className="btn btn-outline btn-sm"
          onClick={async () => {
            if (!confirm('Очистить историю? Рекомендации начнут учиться заново.')) return
            try {
              await api.clearHistory()
              res.reload()
              toast('История очищена')
            } catch (e) {
              toast((e as Error).message)
            }
          }}
        >
          <Trash2 size={16} /> Очистить историю
        </button>
      </div>
    </>
  )
}
