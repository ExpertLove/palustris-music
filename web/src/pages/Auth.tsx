import { ArrowLeft, Eye, EyeOff, MailCheck } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useLocation, useSearch } from 'wouter'
import { Logo } from '../components/common'
import { Aura } from '../components/Shell'
import { api } from '../lib/api'
import { GENRES } from '../lib/data'
import { useAuth } from '../store/auth'

function AuthLayout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <>
      <Aura strong />
      <div className="auth">
        <aside className="auth-side">
          <div className="wave-blob" aria-hidden />
          <Link href="/" className="brand" style={{ padding: 0 }}>
            <Logo />
            <span>Музыка</span>
          </Link>
          <h1>{title}</h1>
          <p className="foot small" style={{ opacity: 0.7 }}>
            Без рекламы. Эквалайзер, тексты песен и «Моя волна» — в одном плеере.
          </p>
        </aside>
        <main className="auth-main">{children}</main>
      </div>
    </>
  )
}

function Password({ value, onChange, autoComplete, placeholder }: { value: string; onChange: (v: string) => void; autoComplete: string; placeholder?: string }) {
  const [show, setShow] = useState(false)
  return (
    <div className="pw">
      <input
        className="input"
        type={show ? 'text' : 'password'}
        autoComplete={autoComplete}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required
        minLength={6}
        placeholder={placeholder}
        style={{ paddingRight: 52 }}
      />
      <button type="button" className="icon-btn" onClick={() => setShow(!show)} aria-label={show ? 'Скрыть пароль' : 'Показать пароль'}>
        {show ? <EyeOff size={18} /> : <Eye size={18} />}
      </button>
    </div>
  )
}

export function Login() {
  const signedIn = useAuth((s) => s.signedIn)
  const next = new URLSearchParams(useSearch()).get('next')
  const [email, setEmail] = useState('')
  const [pw, setPw] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [, navigate] = useLocation()
  return (
    <AuthLayout title="С возвращением">
      <form
        className="auth-card"
        onSubmit={async (e) => {
          e.preventDefault()
          setErr('')
          setBusy(true)
          try {
            const r = await api.login(email.trim(), pw)
            await signedIn(r.token)
            navigate(next && next.startsWith('/') && !next.startsWith('//') ? next : '/')
          } catch (x) {
            setErr((x as Error).message)
          }
          setBusy(false)
        }}
      >
        <h2>Вход</h2>
        <p className="sub">Лайки, плейлисты и волна ждут тебя.</p>
        <label className="field">
          <span>Email</span>
          <input className="input" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </label>
        <label className="field">
          <span>Пароль</span>
          <Password value={pw} onChange={setPw} autoComplete="current-password" />
        </label>
        <Link href="/forgot" className="small muted" style={{ justifySelf: 'end', marginTop: -6 }}>
          Забыл пароль?
        </Link>
        {err && <p className="form-error">{err}</p>}
        <button className="btn btn-primary btn-lg btn-block" disabled={busy}>
          {busy ? 'Входим…' : 'Войти'}
        </button>
        <p className="auth-alt">
          Нет аккаунта? <Link href="/register">Создать</Link>
        </p>
      </form>
    </AuthLayout>
  )
}

export function Register() {
  const signedIn = useAuth((s) => s.signedIn)
  const [step, setStep] = useState(1)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [pw, setPw] = useState('')
  const [consent, setConsent] = useState(false)
  const [genres, setGenres] = useState<string[]>([])
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [, navigate] = useLocation()
  const toggle = (g: string) => setGenres((s) => (s.includes(g) ? s.filter((x) => x !== g) : s.length >= 8 ? s : [...s, g]))

  return (
    <AuthLayout title={step === 1 ? 'Музыка под твой вкус' : 'Что ты слушаешь?'}>
      <form
        className="auth-card"
        onSubmit={async (e) => {
          e.preventDefault()
          setErr('')
          if (step === 1) {
            if (!consent) return setErr('Нужно согласие с условиями и политикой')
            return setStep(2)
          }
          setBusy(true)
          try {
            const r = await api.register({ email: email.trim(), password: pw, name: name.trim(), genres, consent })
            await signedIn(r.token)
            navigate('/')
          } catch (x) {
            setErr((x as Error).message)
            if ((x as { status?: number }).status === 409 || /email/i.test((x as Error).message)) setStep(1)
          }
          setBusy(false)
        }}
      >
        <div className="steps" aria-hidden>
          <i className="on" />
          <i className={step === 2 ? 'on' : ''} />
        </div>
        {step === 1 ? (
          <>
            <h2>Создать аккаунт</h2>
            <p className="sub">Займёт полминуты.</p>
            <label className="field">
              <span>Как тебя зовут</span>
              <input className="input" autoComplete="nickname" maxLength={40} value={name} onChange={(e) => setName(e.target.value)} placeholder="Необязательно" />
            </label>
            <label className="field">
              <span>Email</span>
              <input className="input" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </label>
            <label className="field">
              <span>Пароль</span>
              <Password value={pw} onChange={setPw} autoComplete="new-password" placeholder="Минимум 6 символов" />
            </label>
            <label className="check">
              <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
              <span>
                Согласен с{' '}
                <Link className="link" href="/terms" target="_blank">
                  условиями
                </Link>{' '}
                и{' '}
                <Link className="link" href="/privacy" target="_blank">
                  политикой конфиденциальности
                </Link>
              </span>
            </label>
            {err && <p className="form-error">{err}</p>}
            <button className="btn btn-primary btn-lg btn-block">Дальше</button>
          </>
        ) : (
          <>
            <h2>Любимые жанры</h2>
            <p className="sub">Выбери до восьми — с них начнётся «Моя волна». Можно пропустить.</p>
            <div className="genre-grid">
              {GENRES.map((g) => {
                const Icon = g.icon
                const on = genres.includes(g.id)
                return (
                  <button key={g.id} type="button" className={`chip ${on ? 'on' : ''}`} aria-pressed={on} onClick={() => toggle(g.id)}>
                    <Icon size={16} />
                    {g.title}
                  </button>
                )
              })}
            </div>
            {err && <p className="form-error">{err}</p>}
            <button className="btn btn-primary btn-lg btn-block" disabled={busy}>
              {busy ? 'Создаём…' : genres.length ? 'Начать слушать' : 'Пропустить и начать'}
            </button>
            <button type="button" className="btn btn-ghost btn-block" onClick={() => setStep(1)}>
              <ArrowLeft size={18} /> Назад
            </button>
          </>
        )}
        <p className="auth-alt">
          Уже есть аккаунт? <Link href="/login">Войти</Link>
        </p>
      </form>
    </AuthLayout>
  )
}

export function Forgot() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  return (
    <AuthLayout title="Вернём доступ">
      {sent ? (
        <div className="auth-card">
          <MailCheck size={40} style={{ color: 'var(--accent)' }} />
          <h2>Проверь почту</h2>
          <p className="sub">
            Если аккаунт с адресом <b style={{ color: 'var(--ink)' }}>{email}</b> есть, туда пришла ссылка для нового пароля. Она действует час.
          </p>
          <p className="small faint">Письма нет — загляни в «Спам» или попробуй ещё раз через минуту.</p>
          <Link href="/login" className="btn btn-ghost btn-block">
            Вернуться ко входу
          </Link>
        </div>
      ) : (
        <form
          className="auth-card"
          onSubmit={async (e) => {
            e.preventDefault()
            setErr('')
            setBusy(true)
            try {
              await api.forgot(email.trim())
              setSent(true)
            } catch (x) {
              setErr((x as Error).message)
            }
            setBusy(false)
          }}
        >
          <h2>Забыл пароль</h2>
          <p className="sub">Пришлём ссылку, по которой можно задать новый.</p>
          <label className="field">
            <span>Email</span>
            <input className="input" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </label>
          {err && <p className="form-error">{err}</p>}
          <button className="btn btn-primary btn-lg btn-block" disabled={busy}>
            Отправить ссылку
          </button>
          <p className="auth-alt">
            <Link href="/login">Вспомнил пароль</Link>
          </p>
        </form>
      )}
    </AuthLayout>
  )
}

export function Reset() {
  const search = useSearch()
  const tok = new URLSearchParams(search).get('token') ?? ''
  const signedIn = useAuth((s) => s.signedIn)
  const [pw, setPw] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [, navigate] = useLocation()
  return (
    <AuthLayout title="Новый пароль">
      <form
        className="auth-card"
        onSubmit={async (e) => {
          e.preventDefault()
          setErr('')
          setBusy(true)
          try {
            const r = await api.reset(tok, pw)
            await signedIn(r.token)
            navigate('/')
          } catch (x) {
            setErr((x as Error).message)
          }
          setBusy(false)
        }}
      >
        <h2>Задай новый пароль</h2>
        <p className="sub">После этого выйдем на всех устройствах, кроме этого.</p>
        {!tok && <p className="form-error">В ссылке нет токена. Открой ссылку из письма целиком.</p>}
        <label className="field">
          <span>Новый пароль</span>
          <Password value={pw} onChange={setPw} autoComplete="new-password" placeholder="Минимум 6 символов" />
        </label>
        {err && (
          <p className="form-error">
            {err}{' '}
            {/устарела/.test(err) && (
              <Link className="link" href="/forgot">
                Запросить новую ссылку
              </Link>
            )}
          </p>
        )}
        <button className="btn btn-primary btn-lg btn-block" disabled={busy || !tok || pw.length < 6}>
          Сохранить и войти
        </button>
      </form>
    </AuthLayout>
  )
}
