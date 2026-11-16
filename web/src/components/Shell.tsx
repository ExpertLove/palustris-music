import { ChevronLeft, ChevronRight, Heart, House, Library, Plus, Search, UserRound } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'wouter'
import { initials } from '../lib/format'
import { usePlayer } from '../player/store'
import { useAuth } from '../store/auth'
import { useLibrary } from '../store/library'
import { useUi } from '../store/ui'
import { Collage, Logo } from './common'

const NAV = [
  { href: '/', label: 'Главная', icon: House },
  { href: '/search', label: 'Поиск', icon: Search },
  { href: '/library', label: 'Медиатека', icon: Library },
]

function isActive(loc: string, href: string) {
  return href === '/' ? loc === '/' : loc === href || loc.startsWith(href + '/')
}

export function Sidebar() {
  const [loc] = useLocation()
  const playlists = useLibrary((s) => s.playlists)
  const likes = useLibrary((s) => s.likes.length)
  const me = useAuth((s) => s.me)
  const openDialog = useUi((s) => s.openDialog)
  const srcHref = usePlayer((s) => s.source?.href)
  const playing = usePlayer((s) => s.playing)
  return (
    <nav className="side" aria-label="Навигация">
      <Link href="/" className="brand" aria-label="Музыка — главная">
        <Logo />
        <span>Музыка</span>
      </Link>
      <div className="side-nav">
        {NAV.map(({ href, label, icon: Icon }) => (
          <Link key={href} href={href} className={`side-link ${isActive(loc, href) ? 'active' : ''}`} aria-current={isActive(loc, href) ? 'page' : undefined} title={label}>
            <Icon size={21} />
            <span>{label}</span>
          </Link>
        ))}
      </div>
      <div className="side-sep" />
      <div className="side-head">
        <span>Плейлисты</span>
        <button className="icon-btn sm" onClick={() => openDialog({ kind: 'new-playlist' })} aria-label="Новый плейлист" title="Новый плейлист">
          <Plus size={18} />
        </button>
      </div>
      <div className="side-list">
        <Link href="/liked" className={`side-item ${loc === '/liked' ? 'active' : ''} ${playing && srcHref === '/liked' ? 'playing' : ''}`} title="Мне нравится">
          <span style={{ width: 42, height: 42, borderRadius: 9, overflow: 'hidden', flex: 'none' }}>
            <span className="liked-art">
              <Heart size={18} fill="currentColor" />
            </span>
          </span>
          <span className="meta" style={{ display: 'grid', minWidth: 0 }}>
            <span className="t ellipsis">Мне нравится</span>
            <span className="s">{likes} тр.</span>
          </span>
        </Link>
        {playlists.map((p) => {
          const href = `/playlist/${p.id}`
          return (
            <Link key={p.id} href={href} className={`side-item ${loc === href ? 'active' : ''} ${playing && srcHref === href ? 'playing' : ''}`} title={p.name}>
              <span style={{ width: 42, height: 42, borderRadius: 9, overflow: 'hidden', flex: 'none' }}>
                <Collage thumbs={p.thumbs} label={p.name} />
              </span>
              <span className="meta" style={{ display: 'grid', minWidth: 0 }}>
                <span className="t ellipsis">{p.name}</span>
                <span className="s">{p.count} тр.</span>
              </span>
            </Link>
          )
        })}
      </div>
      {me && (
        <Link href="/account" className="side-user side-item" title="Профиль">
          <span className="avatar">{initials(me.name || me.email)}</span>
          <span className="meta ellipsis" style={{ fontWeight: 600, fontSize: 14 }}>
            {me.name || me.email}
          </span>
        </Link>
      )}
    </nav>
  )
}

export function MobileTabs() {
  const [loc] = useLocation()
  const tabs = [...NAV, { href: '/account', label: 'Профиль', icon: UserRound }]
  return (
    <nav className="tabs" aria-label="Разделы">
      {tabs.map(({ href, label, icon: Icon }) => (
        <Link key={href} href={href} className={`tab ${isActive(loc, href) ? 'active' : ''}`} aria-current={isActive(loc, href) ? 'page' : undefined}>
          <Icon size={22} />
          {label}
        </Link>
      ))}
    </nav>
  )
}

/** Верхняя полоса: назад/вперёд, заголовок при прокрутке, свой контент справа. */
export function TopBar({ title, children }: { title?: string; children?: ReactNode }) {
  const [scrolled, setScrolled] = useState(false)
  const me = useAuth((s) => s.me)
  useEffect(() => {
    const main = document.querySelector('.main')
    if (!main) return
    const on = () => setScrolled(main.scrollTop > 40)
    on()
    main.addEventListener('scroll', on, { passive: true })
    return () => main.removeEventListener('scroll', on)
  }, [])
  return (
    <header className={`topbar ${scrolled ? 'scrolled' : ''}`}>
      <div className="nav-arrows">
        <button className="icon-btn sm" onClick={() => history.back()} aria-label="Назад">
          <ChevronLeft size={20} />
        </button>
        <button className="icon-btn sm fwd" onClick={() => history.forward()} aria-label="Вперёд">
          <ChevronRight size={20} />
        </button>
      </div>
      {title && <span className="title ellipsis">{title}</span>}
      <span className="spacer" />
      {children}
      {me && (
        <Link href="/account" className="avatar" aria-label="Профиль" title={me.name || me.email} style={{ marginLeft: 6 }}>
          {initials(me.name || me.email)}
        </Link>
      )}
    </header>
  )
}

export function Aura({ strong }: { strong?: boolean }) {
  return (
    <>
      <div className={`aura ${strong ? 'strong' : ''}`} aria-hidden />
      <div className="aura-grain" aria-hidden />
    </>
  )
}
