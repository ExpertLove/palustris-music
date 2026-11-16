import { lazy, Suspense, useEffect, type ReactNode } from 'react'
import { Redirect, Route, Router, Switch, useLocation } from 'wouter'
import { Logo } from './components/common'
import { Overlays } from './components/Overlays'
import { Dock, MiniPlayer, NowPlaying, SidePanel } from './components/Player'
import { Aura, MobileTabs, Sidebar } from './components/Shell'
import { usePaletteFromCover, useHotkeys } from './player/effects'
import { restorePlayer } from './player/store'
import { useAuth } from './store/auth'
import { useLibrary } from './store/library'
import Home from './pages/Home'
import Search from './pages/Search'
import Library from './pages/Library'
import { AlbumPage, ArtistPage, LikedPage, MoodPage, MoodsPage, PlaylistPage, SharedPage, TopPage } from './pages/Collections'
import { Forgot, Login, Register, Reset } from './pages/Auth'
import Legal from './pages/Legal'

const Account = lazy(() => import('./pages/Account'))
const Admin = lazy(() => import('./pages/Admin'))
const Landing = lazy(() => import('./pages/Landing'))

const base = import.meta.env.BASE_URL.replace(/\/$/, '')

function Splash() {
  return (
    <div style={{ minHeight: '100dvh', display: 'grid', placeItems: 'center' }} aria-busy>
      <Logo className="brand-mark" />
    </div>
  )
}

let restored = false

function Shell({ children }: { children: ReactNode }) {
  const [loc] = useLocation()
  usePaletteFromCover()
  useHotkeys()
  useEffect(() => {
    if (!restored) {
      restored = true
      restorePlayer()
    }
    void useLibrary.getState().load()
  }, [])
  useEffect(() => {
    document.querySelector('.main')?.scrollTo({ top: 0 })
  }, [loc])
  return (
    <>
      <Aura />
      <div className="app">
        <Sidebar />
        <main className="main" id="main">
          <Suspense fallback={null}>{children}</Suspense>
        </main>
        <SidePanel />
      </div>
      <Dock />
      <MiniPlayer />
      <MobileTabs />
      <NowPlaying />
      <Overlays />
    </>
  )
}

function UserRoutes() {
  return (
    <Shell>
      <Switch>
        <Route path="/" component={Home} />
        <Route path="/search" component={Search} />
        <Route path="/library" component={Library} />
        <Route path="/liked" component={LikedPage} />
        <Route path="/playlist/:id" component={PlaylistPage} />
        <Route path="/album/:id" component={AlbumPage} />
        <Route path="/artist/n/:name">{() => <ArtistPage byName />}</Route>
        <Route path="/artist/:id">{() => <ArtistPage />}</Route>
        <Route path="/moods" component={MoodsPage} />
        <Route path="/mood/:id" component={MoodPage} />
        <Route path="/top" component={TopPage} />
        <Route path="/account" component={Account} />
        <Route path="/admin" component={Admin} />
        <Route path="/s/:slug" component={SharedPage} />
        <Route path="/login">
          <Redirect to="/" replace />
        </Route>
        <Route path="/register">
          <Redirect to="/" replace />
        </Route>
        <Route>
          <div className="main-inner">
            <div className="empty" style={{ paddingTop: 120 }}>
              <h3>Такой страницы нет</h3>
              <p>Возможно, ссылка устарела.</p>
              <a className="btn btn-light" href={`${base}/`}>
                На главную
              </a>
            </div>
          </div>
        </Route>
      </Switch>
    </Shell>
  )
}

function GuestRoutes() {
  const [loc] = useLocation()
  return (
    <Suspense fallback={<Splash />}>
      <Switch>
        <Route path="/" component={Landing} />
        <Route path="/login" component={Login} />
        <Route path="/register" component={Register} />
        <Route path="/forgot" component={Forgot} />
        <Route path="/s/:slug">
          {() => (
            <>
              <Aura />
              <div style={{ position: 'relative', zIndex: 1 }}>
                <nav className="lp-wrap lp-nav">
                  <a href={`${base}/`} className="brand" style={{ padding: 0 }}>
                    <Logo />
                    <span>Музыка</span>
                  </a>
                  <a href={`${base}/login`} className="btn btn-ghost btn-sm">
                    Войти
                  </a>
                </nav>
                <SharedPage />
              </div>
              <Overlays />
            </>
          )}
        </Route>
        <Route>
          <Redirect to={`/login?next=${encodeURIComponent(loc)}`} replace />
        </Route>
      </Switch>
    </Suspense>
  )
}

export default function App() {
  const status = useAuth((s) => s.status)
  useEffect(() => {
    void useAuth.getState().init()
  }, [])
  useEffect(() => {
    if (status === 'guest') useLibrary.getState().reset()
  }, [status])

  return (
    <Router base={base}>
      <Switch>
        {/* Эти страницы работают в любом состоянии входа. */}
        <Route path="/privacy">{() => <Legal kind="privacy" />}</Route>
        <Route path="/terms">{() => <Legal kind="terms" />}</Route>
        <Route path="/reset" component={Reset} />
        <Route>{status === 'loading' ? <Splash /> : status === 'user' ? <UserRoutes /> : <GuestRoutes />}</Route>
      </Switch>
    </Router>
  )
}
