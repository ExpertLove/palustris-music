import { Keyboard, LogOut, MailCheck, MailWarning, Shield, SlidersHorizontal, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'wouter'
import { Dialog } from '../components/Overlays'
import { TopBar } from '../components/Shell'
import { api } from '../lib/api'
import { GENRES } from '../lib/data'
import { initials, plural } from '../lib/format'
import { engine } from '../player/engine'
import { usePlayer } from '../player/store'
import { useAuth } from '../store/auth'
import { useLibrary } from '../store/library'
import { toast, useUi } from '../store/ui'

export default function Account() {
  const me = useAuth((s) => s.me)
  const refresh = useAuth((s) => s.refresh)
  const logout = useAuth((s) => s.logout)
  const dropSession = useAuth((s) => s.dropSession)
  const resetLib = useLibrary((s) => s.reset)
  const openDialog = useUi((s) => s.openDialog)
  const [name, setName] = useState('')
  const [genres, setGenres] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  const [pw, setPw] = useState(false)
  const [del, setDel] = useState(false)

  useEffect(() => {
    refresh()
  }, [refresh])
  useEffect(() => {
    if (me) {
      setName(me.name)
      setGenres(me.genres)
    }
  }, [me])

  if (!me) return null
  const dirty = name.trim() !== me.name || genres.join() !== me.genres.join()
  const toggleGenre = (g: string) => setGenres((s) => (s.includes(g) ? s.filter((x) => x !== g) : s.length >= 8 ? s : [...s, g]))
  const signOut = async () => {
    usePlayer.getState().pause()
    engine.stop()
    localStorage.removeItem('music.player.v2')
    resetLib()
    await logout()
  }

  return (
    <>
      <TopBar title="Профиль" />
      <div className="main-inner">
        <div className="acc">
          <div className="acc-hero">
            <span className="avatar lg">{initials(me.name || me.email)}</span>
            <div style={{ display: 'grid', gap: 6, minWidth: 0 }}>
              <h1 className="h1 ellipsis">{me.name || 'Без имени'}</h1>
              <div className="muted ellipsis" style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                {me.email}
                {me.verified ? (
                  <span className="badge ok">
                    <MailCheck size={14} /> подтверждена
                  </span>
                ) : (
                  <span className="badge warn">
                    <MailWarning size={14} /> не подтверждена
                  </span>
                )}
              </div>
              <div className="small faint">С нами с {new Date(me.created * 1000).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })}</div>
            </div>
          </div>

          <div className="acc-stats">
            <Stat n={me.likes} label={plural(me.likes, 'лайк', 'лайка', 'лайков')} href="/liked" />
            <Stat n={me.playlists} label={plural(me.playlists, 'плейлист', 'плейлиста', 'плейлистов')} href="/library" />
            <Stat n={me.plays} label={plural(me.plays, 'прослушивание', 'прослушивания', 'прослушиваний')} href="/library?tab=history" />
            <Stat n={me.genres.length} label={plural(me.genres.length, 'жанр', 'жанра', 'жанров')} />
          </div>

          {!me.verified && <VerifyNote />}

          <section className="acc-card">
            <h2>Профиль</h2>
            <p>Имя видно в плейлистах, которыми ты делишься. Жанры помогают «Моей волне» на старте.</p>
            <label className="field" style={{ marginBottom: 18 }}>
              <span>Имя</span>
              <input className="input" value={name} maxLength={40} onChange={(e) => setName(e.target.value)} placeholder="Как тебя зовут" />
            </label>
            <div className="field">
              <span>
                Любимые жанры · {genres.length}/8
              </span>
              <div className="genre-grid">
                {GENRES.map((g) => {
                  const Icon = g.icon
                  const on = genres.includes(g.id)
                  return (
                    <button key={g.id} type="button" className={`chip ${on ? 'on' : ''}`} aria-pressed={on} onClick={() => toggleGenre(g.id)}>
                      <Icon size={16} />
                      {g.title}
                    </button>
                  )
                })}
              </div>
            </div>
            <div style={{ marginTop: 20 }}>
              <button
                className="btn btn-primary"
                disabled={!dirty || saving}
                onClick={async () => {
                  setSaving(true)
                  try {
                    await api.setProfile(name.trim(), genres)
                    await refresh()
                    toast('Сохранено')
                  } catch (e) {
                    toast((e as Error).message)
                  }
                  setSaving(false)
                }}
              >
                Сохранить
              </button>
            </div>
          </section>

          <section className="acc-card">
            <h2>Звук и управление</h2>
            <p>Эквалайзер, плавные переходы, ровная громкость и скорость.</p>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <button className="btn btn-ghost" onClick={() => openDialog({ kind: 'eq' })}>
                <SlidersHorizontal size={18} /> Настроить звук
              </button>
              <button className="btn btn-ghost" onClick={() => openDialog({ kind: 'shortcuts' })}>
                <Keyboard size={18} /> Горячие клавиши
              </button>
            </div>
          </section>

          <section className="acc-card">
            <h2>Безопасность</h2>
            <p>
              Сейчас вход выполнен на {me.sessions} {plural(me.sessions, 'устройстве', 'устройствах', 'устройствах')}.
            </p>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              <button className="btn btn-ghost" onClick={() => setPw(true)}>
                <Shield size={18} /> Сменить пароль
              </button>
              {me.sessions > 1 && (
                <button
                  className="btn btn-ghost"
                  onClick={async () => {
                    try {
                      const r = await api.logoutOthers()
                      await refresh()
                      toast(`Выход выполнен на других устройствах: ${r.removed}`)
                    } catch (e) {
                      toast((e as Error).message)
                    }
                  }}
                >
                  Выйти на других устройствах
                </button>
              )}
              <button className="btn btn-outline" onClick={signOut}>
                <LogOut size={18} /> Выйти
              </button>
            </div>
          </section>

          <section className="acc-card">
            <h2>Данные</h2>
            <p>
              Храним только то, без чего сервис не работает. Подробно — в <Link className="link" href="/privacy">политике конфиденциальности</Link>.
            </p>
            <button className="btn btn-danger" onClick={() => setDel(true)}>
              <Trash2 size={18} /> Удалить аккаунт
            </button>
          </section>
          <p className="small faint">
            <Link href="/terms" className="hover-link">
              Условия использования
            </Link>{' '}
            ·{' '}
            <Link href="/privacy" className="hover-link">
              Конфиденциальность
            </Link>
          </p>
        </div>
      </div>
      {pw && <PasswordDialog onClose={() => setPw(false)} />}
      {del && (
        <Dialog title="Удалить аккаунт?" onClose={() => setDel(false)} width={440}>
          <p className="muted">Удалятся профиль, лайки, история и плейлисты. Восстановить не получится.</p>
          <div className="dialog-actions">
            <button className="btn btn-ghost" onClick={() => setDel(false)}>
              Отмена
            </button>
            <button
              className="btn btn-danger"
              onClick={async () => {
                try {
                  await api.deleteAccount()
                  engine.stop()
                  localStorage.removeItem('music.player.v2')
                  resetLib()
                  dropSession()
                  toast('Аккаунт удалён')
                } catch (e) {
                  toast((e as Error).message)
                }
              }}
            >
              Удалить навсегда
            </button>
          </div>
        </Dialog>
      )}
    </>
  )
}

function Stat({ n, label, href }: { n: number; label: string; href?: string }) {
  const inner = (
    <>
      <b>{n}</b>
      <span>{label}</span>
    </>
  )
  return href ? (
    <Link href={href} className="acc-stat">
      {inner}
    </Link>
  ) : (
    <div className="acc-stat">{inner}</div>
  )
}

function VerifyNote() {
  const [sent, setSent] = useState(false)
  return (
    <div className="verify-note">
      <MailWarning size={20} style={{ flex: 'none', color: 'var(--warn)' }} />
      <span style={{ flex: 1 }}>Подтверди почту — так получится восстановить доступ, если забудешь пароль.</span>
      <button
        className="btn btn-sm btn-ghost"
        disabled={sent}
        onClick={async () => {
          try {
            const r = await api.resend()
            if (r.verified) toast('Почта уже подтверждена')
            else toast('Письмо отправлено, проверь почту')
            setSent(true)
          } catch (e) {
            toast((e as Error).message)
          }
        }}
      >
        {sent ? 'Отправлено' : 'Отправить письмо'}
      </button>
    </div>
  )
}

function PasswordDialog({ onClose }: { onClose: () => void }) {
  const [old, setOld] = useState('')
  const [nw, setNw] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <Dialog title="Сменить пароль" onClose={onClose}>
      <form
        style={{ display: 'grid', gap: 14, marginTop: 16 }}
        onSubmit={async (e) => {
          e.preventDefault()
          setErr('')
          setBusy(true)
          try {
            const r = await api.changePassword(old, nw)
            toast(r.loggedOut ? `Пароль изменён. Другие устройства вышли: ${r.loggedOut}` : 'Пароль изменён')
            onClose()
          } catch (x) {
            setErr((x as Error).message)
          }
          setBusy(false)
        }}
      >
        <label className="field">
          <span>Текущий пароль</span>
          <input className="input" type="password" autoComplete="current-password" value={old} onChange={(e) => setOld(e.target.value)} required />
        </label>
        <label className="field">
          <span>Новый пароль</span>
          <input className="input" type="password" autoComplete="new-password" minLength={6} value={nw} onChange={(e) => setNw(e.target.value)} required placeholder="Минимум 6 символов" />
        </label>
        <p className="small faint">После смены пароля выйдем на остальных устройствах.</p>
        {err && <p className="form-error">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Отмена
          </button>
          <button className="btn btn-primary" disabled={busy || nw.length < 6 || !old}>
            Сменить
          </button>
        </div>
      </form>
    </Dialog>
  )
}
