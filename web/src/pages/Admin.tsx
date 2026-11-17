import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  Clock,
  Download,
  FileUp,
  KeyRound,
  Pause,
  Pencil,
  Play,
  Plus,
  Power,
  RefreshCw,
  RotateCcw,
  ScrollText,
  ShieldAlert,
  ShieldCheck,
  Trash2,
  Users,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useSearch } from 'wouter'
import { Dialog } from '../components/Overlays'
import { TopBar } from '../components/Shell'
import { adminApi } from '../lib/api'
import { ago, plural } from '../lib/format'
import type { AccountTest, AdminOverview, AdminUser, DlEvent, YtAccount } from '../lib/types'
import { useAuth } from '../store/auth'
import { toast } from '../store/ui'
import '../styles/admin.css'

type Tab = 'overview' | 'accounts' | 'downloads' | 'logs' | 'users'

const TABS: { id: Tab; label: string; icon: typeof Activity }[] = [
  { id: 'overview', label: 'Обзор', icon: Activity },
  { id: 'accounts', label: 'YouTube', icon: KeyRound },
  { id: 'downloads', label: 'Загрузки', icon: Download },
  { id: 'logs', label: 'Логи', icon: ScrollText },
  { id: 'users', label: 'Люди', icon: Users },
]

export default function Admin() {
  const me = useAuth((s) => s.me)
  const search = useSearch()
  const [, navigate] = useLocation()
  const tab = (new URLSearchParams(search).get('tab') as Tab) || 'overview'
  const setTab = (t: Tab) => navigate(t === 'overview' ? '/admin' : `/admin?tab=${t}`, { replace: true })

  if (!me) return null
  if (!me.admin) {
    return (
      <>
        <TopBar title="Админка" />
        <div className="main-inner">
          <div className="empty" style={{ paddingTop: 120 }}>
            <h3>Нет доступа</h3>
            <p>Эта страница только для администраторов.</p>
          </div>
        </div>
      </>
    )
  }

  return (
    <>
      <TopBar title="Админка" />
      <div className="main-inner adm">
        <div className="adm-head">
          <h1 className="page-title" style={{ margin: 0 }}>
            Админка
          </h1>
          <div className="seg adm-tabs" role="tablist">
            {TABS.map(({ id, label, icon: Icon }) => (
              <button key={id} role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>
                <Icon size={16} />
                <span>{label}</span>
              </button>
            ))}
          </div>
        </div>
        {tab === 'overview' && <Overview go={setTab} />}
        {tab === 'accounts' && <Accounts />}
        {tab === 'downloads' && <Downloads />}
        {tab === 'logs' && <Logs />}
        {tab === 'users' && <UsersTab />}
      </div>
    </>
  )
}

// ───────── общее ─────────

/** Загрузка с периодическим обновлением, пока вкладка видна. */
function usePoll<T>(load: () => Promise<T>, every: number | null, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const loadRef = useRef(load)
  loadRef.current = load
  const reload = useCallback(async () => {
    setBusy(true)
    try {
      setData(await loadRef.current())
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
    setBusy(false)
  }, [])
  useEffect(() => {
    void reload()
    if (!every) return
    const t = setInterval(() => document.visibilityState === 'visible' && void reload(), every)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reload, every, ...deps])
  return { data, error, busy, reload, setData }
}

const fmtBytes = (b: number) => {
  if (b >= 1024 ** 3) return `${(b / 1024 ** 3).toFixed(1)} ГБ`
  if (b >= 1024 ** 2) return `${Math.round(b / 1024 ** 2)} МБ`
  return `${Math.round(b / 1024)} КБ`
}
const fmtClock = (ts: number) => new Date(ts * 1000).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
const fmtDateTime = (ts: number) =>
  new Date(ts * 1000).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
const agoOr = (ts: number, none = 'никогда') => (ts > 0 ? ago(ts) : none)
const fmtUptime = (s: number) => {
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  return d > 0 ? `${d} д ${h} ч` : h > 0 ? `${h} ч ${m} мин` : `${m} мин`
}

/** Ошибки yt-dlp тянут за собой одинаковый хвост со ссылками на FAQ — он только мешает читать. */
const shortErr = (e: string) =>
  e
    .replace(/^ERROR:\s*/, '')
    .replace(/\s*This helps protect our community\..*$/s, '')
    .replace(/\s*Use --cookies-from-browser or --cookies.*$/s, '')
    .trim()

const KIND: Record<string, string> = {
  bot: 'проверка на бота',
  cookies: 'cookies устарели',
  ratelimit: 'лимит запросов',
  video: 'видео недоступно',
  network: 'сеть',
  other: 'ошибка',
}

function RefreshBtn({ busy, onClick }: { busy: boolean; onClick: () => void }) {
  return (
    <button className="icon-btn sm" onClick={onClick} aria-label="Обновить" title="Обновить" disabled={busy}>
      <RefreshCw size={17} className={busy ? 'adm-spin' : ''} />
    </button>
  )
}

function Tile({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'ok' | 'warn' | 'bad' }) {
  return (
    <div className={`adm-tile ${tone ?? ''}`}>
      <span className="l">{label}</span>
      <span className="v">{value}</span>
      {sub && <span className="s">{sub}</span>}
    </div>
  )
}

// ───────── обзор ─────────

function Overview({ go }: { go: (t: Tab) => void }) {
  const { data: o, error, busy, reload } = usePoll<AdminOverview>(adminApi.overview, 15000)
  if (error && !o) return <p className="adm-error">{error}</p>
  if (!o) return <div className="adm-skel" />
  const flagged = o.ipFlaggedUntil > o.now
  const blocked = o.ytBlockedSince != null
  const noAccounts = o.accounts.available === 0
  const failing = o.hour.fail > 0 && o.hour.ok === 0
  const fb = o.fallback.hour
  const tone = blocked
    ? fb.ok === 0 && fb.miss > 0
      ? 'bad'
      : 'warn'
    : flagged && noAccounts
      ? 'bad'
      : flagged || failing
        ? 'warn'
        : 'ok'
  const cachePct = Math.min(100, Math.round((o.cache.bytes / Math.max(1, o.cache.maxBytes)) * 100))
  const diskPct = o.disk ? Math.round(((o.disk.size - o.disk.avail) / o.disk.size) * 100) : 0

  return (
    <div className="adm-stack">
      <section className={`adm-hero ${tone}`}>
        <div className="ico">{tone === 'ok' ? <ShieldCheck size={28} /> : <ShieldAlert size={28} />}</div>
        <div className="txt">
          <h2>
            {tone === 'ok'
              ? 'Загрузка треков работает'
              : blocked
                ? 'YouTube блокирует сервер — новые треки ищем в запасных источниках'
                : flagged && noAccounts
                ? 'YouTube блокирует сервер, аккаунтов нет'
                : flagged
                  ? 'YouTube блокирует IP — работаем через аккаунты'
                  : 'Есть ошибки загрузки'}
          </h2>
          <p>
            {blocked ? (
              <>
                С {fmtClock(o.ytBlockedSince!)} YouTube просит подтвердить, что сервер не бот. На каждый трек его не дёргаем
                {o.ytNextProbe ? `, следующая проверка — после ${fmtClock(o.ytNextProbe)}` : ''}. За час такая же запись
                нашлась для {fb.ok} {plural(fb.ok, 'трека', 'треков', 'треков')}, не нашлась — для {fb.miss}. Уже скачанные треки играют
                как обычно.
              </>
            ) : flagged ? (
              <>
                Без аккаунта YouTube отвечает «подтвердите, что вы не бот». Следующая проверка без аккаунта после{' '}
                {fmtClock(o.ipFlaggedUntil)}.{' '}
                {noAccounts ? 'Добавьте аккаунт YouTube, чтобы новые треки снова играли.' : `Доступно аккаунтов: ${o.accounts.available}.`}
              </>
            ) : (
              <>
                За час скачано {o.hour.ok} {plural(o.hour.ok, 'трек', 'трека', 'треков')}
                {o.hour.fail > 0 ? `, не удалось — ${o.hour.fail}` : ', без сбоев'}. Аккаунтов YouTube: {o.accounts.total}
                {o.accounts.total > 0 && `, доступно ${o.accounts.available}`}.
              </>
            )}
          </p>
        </div>
        <div className="act">
          {noAccounts && (
            <button className="btn btn-primary btn-sm" onClick={() => go('accounts')}>
              <Plus size={16} /> Добавить аккаунт
            </button>
          )}
          <RefreshBtn busy={busy} onClick={reload} />
        </div>
      </section>

      <div className="adm-tiles">
        <Tile
          label="Треков за час"
          value={o.hour.ok}
          sub={o.hour.fail ? `не скачалось: ${o.hour.fail}` : 'без сбоев'}
          tone={o.hour.fail && !o.hour.ok ? 'bad' : o.hour.fail ? 'warn' : undefined}
        />
        <Tile label="Треков за сутки" value={o.day.ok} sub={o.day.fail ? `не скачалось: ${o.day.fail}` : 'без сбоев'} tone={o.day.fail > o.day.ok ? 'warn' : undefined} />
        <Tile
          label="Аккаунты YouTube"
          value={`${o.accounts.available} / ${o.accounts.total}`}
          sub="доступно сейчас"
          tone={o.accounts.total === 0 ? undefined : o.accounts.available === 0 ? 'bad' : undefined}
        />
        <Tile
          label="Запасные источники за сутки"
          value={o.fallback.day.ok}
          sub={o.fallback.day.ok + o.fallback.day.miss === 0 ? 'не понадобились' : `такой же записи не нашлось: ${o.fallback.day.miss}`}
          tone={o.fallback.day.miss > o.fallback.day.ok ? 'warn' : undefined}
        />
        <Tile label="Прослушиваний за сутки" value={o.playsDay} sub={`активных за неделю: ${o.activeUsers} из ${o.users}`} />
        <Tile
          label="Кэш треков"
          value={fmtBytes(o.cache.bytes)}
          sub={
            <>
              <span className="adm-bar">
                <i style={{ width: `${cachePct}%` }} />
              </span>
              {o.cache.files} файлов · лимит {fmtBytes(o.cache.maxBytes)}
            </>
          }
        />
        {o.disk && (
          <Tile
            label="Диск"
            value={`свободно ${fmtBytes(o.disk.avail)}`}
            sub={
              <>
                <span className="adm-bar">
                  <i style={{ width: `${diskPct}%` }} />
                </span>
                занято {diskPct}% из {fmtBytes(o.disk.size)}
              </>
            }
            tone={diskPct > 90 ? 'bad' : diskPct > 80 ? 'warn' : undefined}
          />
        )}
        <Tile label="yt-dlp" value={<span className="mono">{o.ytdlp}</span>} sub="обновляется каждую ночь в 04:15" />
        <Tile label="Сервис работает" value={fmtUptime(o.now - o.startedAt)} sub={`с ${fmtDateTime(o.startedAt)}`} />
      </div>

      {o.lastError && (
        <section className="acc-card">
          <h2>Последняя ошибка загрузки</h2>
          <p>{fmtDateTime(o.lastError.at)}</p>
          <pre className="adm-pre">{shortErr(o.lastError.err)}</pre>
          <button className="btn btn-ghost btn-sm" style={{ marginTop: 12 }} onClick={() => go('downloads')}>
            Все загрузки
          </button>
        </section>
      )}
    </div>
  )
}

// ───────── аккаунты YouTube ─────────

const STATUS: Record<YtAccount['status'], { label: string; tone: string }> = {
  ok: { label: 'работает', tone: 'ok' },
  new: { label: 'ещё не использовался', tone: 'info' },
  cooldown: { label: 'на паузе', tone: 'warn' },
  dead: { label: 'cookies устарели', tone: 'bad' },
  off: { label: 'выключен', tone: 'muted' },
}

function Accounts() {
  const { data, error, busy, reload } = usePoll<YtAccount[]>(adminApi.accounts, 20000)
  const [adding, setAdding] = useState(false)
  const [help, setHelp] = useState(false)
  useEffect(() => {
    if (data && data.length === 0) setHelp(true)
  }, [data])

  return (
    <div className="adm-stack">
      <div className="adm-bar-row">
        <p className="muted" style={{ flex: 1, minWidth: 240 }}>
          Сервер качает треки без аккаунта. Когда YouTube требует «подтвердить, что вы не бот», загрузка идёт через эти аккаунты по очереди.
          Аккаунт, который тоже получил проверку, отдыхает 30 минут.
        </p>
        <RefreshBtn busy={busy} onClick={reload} />
        <button className="btn btn-primary" onClick={() => setAdding(true)}>
          <Plus size={18} /> Добавить аккаунт
        </button>
      </div>

      <section className="acc-card adm-help">
        <button className="adm-help-toggle" onClick={() => setHelp((v) => !v)} aria-expanded={help}>
          <span>Как получить cookies аккаунта</span>
          <ChevronDown size={18} style={{ transform: help ? 'rotate(180deg)' : undefined, transition: 'transform .2s' }} />
        </button>
        {help && (
          <ol>
            <li>
              Заведите <b>отдельный</b> Google-аккаунт. Основной не используйте: YouTube может заблокировать его за автоматические загрузки.
            </li>
            <li>
              Поставьте расширение: в Chrome — <b>«Get cookies.txt LOCALLY»</b>, в Firefox — <b>«cookies.txt»</b>. Подойдёт и JSON из Cookie-Editor.
              Разрешите расширению работать в режиме инкогнито.
            </li>
            <li>
              Откройте <b>окно инкогнито</b>, войдите в аккаунт на <span className="mono">music.youtube.com</span> и включите любой трек.
            </li>
            <li>В этом же окне выгрузите cookies текущего сайта в файл.</li>
            <li>
              <b>Сразу закройте окно инкогнито</b>, не выходя из аккаунта. Иначе браузер обновит сессию, и выгруженные cookies перестанут работать.
            </li>
            <li>Нажмите «Добавить аккаунт» и выберите файл. Сайт сохранит только cookies youtube.com и google.com, остальное выбросит.</li>
          </ol>
        )}
      </section>

      {error && !data && <p className="adm-error">{error}</p>}
      {data && data.length === 0 && (
        <div className="empty">
          <div className="ico">
            <KeyRound size={26} />
          </div>
          <h3>Аккаунтов пока нет</h3>
          <p>Добавьте хотя бы один, чтобы треки играли, даже когда YouTube блокирует сервер.</p>
        </div>
      )}
      <div className="adm-accounts">
        {data?.map((a) => (
          <AccountCard key={a.id} a={a} reload={reload} />
        ))}
      </div>
      {adding && (
        <CookiesDialog
          title="Новый аккаунт YouTube"
          withLabel
          onClose={() => setAdding(false)}
          onSubmit={async (label, cookies) => {
            const r = await adminApi.addAccount(label, cookies)
            toast(`Аккаунт добавлен: ${r.cookies} cookies`)
            setAdding(false)
            await reload()
          }}
        />
      )}
    </div>
  )
}

function AccountCard({ a, reload }: { a: YtAccount; reload: () => Promise<void> }) {
  const [testing, setTesting] = useState(false)
  const [test, setTest] = useState<AccountTest | null>(null)
  const [replace, setReplace] = useState(false)
  const [rename, setRename] = useState(false)
  const [confirmDel, setConfirmDel] = useState(false)
  const st = STATUS[a.status]

  const act = async (fn: () => Promise<unknown>, ok?: string) => {
    try {
      await fn()
      if (ok) toast(ok)
      await reload()
    } catch (e) {
      toast((e as Error).message)
    }
  }

  return (
    <section className={`adm-acc ${a.status}`}>
      <div className="top">
        <div style={{ minWidth: 0 }}>
          <h3 className="ellipsis">{a.label}</h3>
          <div className="small faint">
            #{a.id} · добавлен {agoOr(a.created)}
            {a.updated !== a.created && ` · cookies обновлены ${agoOr(a.updated)}`}
          </div>
        </div>
        <span className={`adm-badge ${st.tone}`}>
          {a.status === 'cooldown' ? `пауза до ${fmtClock(a.cooldownUntil)}` : st.label}
        </span>
      </div>

      <div className="nums">
        <div>
          <b>{a.okCount}</b>
          <span>успешных</span>
        </div>
        <div>
          <b>{a.hourOk}</b>
          <span>за час</span>
        </div>
        <div>
          <b className={a.errCount ? 'bad' : ''}>{a.errCount}</b>
          <span>ошибок</span>
        </div>
        <div>
          <b>{a.cookies}</b>
          <span>{a.loggedIn ? 'cookies, вход есть' : 'cookies, входа нет'}</span>
        </div>
      </div>

      <div className="small muted meta">
        <span>
          <Clock size={13} /> использовался {agoOr(a.lastUsed)}
        </span>
        <span>
          <CheckCircle2 size={13} /> последний успех {agoOr(a.lastOk)}
        </span>
      </div>

      {a.note && (
        <div className="adm-note">
          <AlertTriangle size={15} />
          <span>
            {a.lastErr > 0 && <b>{agoOr(a.lastErr)}: </b>}
            {shortErr(a.note)}
          </span>
        </div>
      )}

      {test && (
        <div className={`adm-test ${test.ok ? 'ok' : 'bad'}`}>
          {test.ok ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}
          <span>
            {test.loggedIn === false
              ? `${test.error}.`
              : test.ok
                ? `Работает: YouTube видит вход${test.loggedIn === null ? ' (не удалось проверить)' : ''}, аудио отдаётся (формат ${test.format}), ${(test.ms / 1000).toFixed(1)} с.`
                : `${KIND[test.kind ?? 'other'] ?? 'Ошибка'}: ${shortErr(test.error ?? '')}`}
          </span>
        </div>
      )}

      <div className="actions">
        <button
          className="btn btn-ghost btn-sm"
          disabled={testing}
          onClick={async () => {
            setTesting(true)
            setTest(null)
            try {
              setTest(await adminApi.testAccount(a.id))
              await reload()
            } catch (e) {
              toast((e as Error).message)
            }
            setTesting(false)
          }}
        >
          {testing ? <RefreshCw size={16} className="adm-spin" /> : <Play size={16} />} {testing ? 'Проверяю…' : 'Проверить'}
        </button>
        <button className="btn btn-ghost btn-sm" onClick={() => setReplace(true)}>
          <FileUp size={16} /> Новые cookies
        </button>
        {(a.status === 'cooldown' || a.status === 'dead') && (
          <button className="btn btn-ghost btn-sm" onClick={() => act(() => adminApi.patchAccount(a.id, { reset: true }), 'Пауза снята')}>
            <RotateCcw size={16} /> Снять паузу
          </button>
        )}
        <button
          className="btn btn-ghost btn-sm"
          onClick={() => act(() => adminApi.patchAccount(a.id, { enabled: !a.enabled }), a.enabled ? 'Аккаунт выключен' : 'Аккаунт включён')}
        >
          {a.enabled ? <Pause size={16} /> : <Power size={16} />} {a.enabled ? 'Выключить' : 'Включить'}
        </button>
        <button className="icon-btn sm" onClick={() => setRename(true)} aria-label="Переименовать" title="Переименовать">
          <Pencil size={16} />
        </button>
        <button className="icon-btn sm adm-del" onClick={() => setConfirmDel(true)} aria-label="Удалить" title="Удалить">
          <Trash2 size={16} />
        </button>
      </div>

      {replace && (
        <CookiesDialog
          title={`Новые cookies для «${a.label}»`}
          onClose={() => setReplace(false)}
          onSubmit={async (_, cookies) => {
            const r = await adminApi.replaceCookies(a.id, cookies)
            toast(`Cookies обновлены: ${r.cookies}`)
            setReplace(false)
            await reload()
          }}
        />
      )}
      {rename && <RenameDialog a={a} onClose={() => setRename(false)} onDone={reload} />}
      {confirmDel && (
        <Dialog title="Удалить аккаунт?" onClose={() => setConfirmDel(false)} width={420}>
          <p className="muted">
            «{a.label}» и его cookies удалятся с сервера. Сам Google-аккаунт останется, просто сайт перестанет им пользоваться.
          </p>
          <div className="dialog-actions">
            <button className="btn btn-ghost" onClick={() => setConfirmDel(false)}>
              Отмена
            </button>
            <button className="btn btn-danger" onClick={() => act(() => adminApi.deleteAccount(a.id), 'Аккаунт удалён')}>
              Удалить
            </button>
          </div>
        </Dialog>
      )}
    </section>
  )
}

function RenameDialog({ a, onClose, onDone }: { a: YtAccount; onClose: () => void; onDone: () => Promise<void> }) {
  const [label, setLabel] = useState(a.label)
  return (
    <Dialog title="Название аккаунта" onClose={onClose} width={420}>
      <form
        onSubmit={async (e) => {
          e.preventDefault()
          try {
            await adminApi.patchAccount(a.id, { label })
            onClose()
            await onDone()
          } catch (err) {
            toast((err as Error).message)
          }
        }}
      >
        <input className="input" value={label} maxLength={60} autoFocus onChange={(e) => setLabel(e.target.value)} />
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Отмена
          </button>
          <button className="btn btn-primary" disabled={!label.trim()}>
            Сохранить
          </button>
        </div>
      </form>
    </Dialog>
  )
}

function CookiesDialog({
  title,
  withLabel,
  onClose,
  onSubmit,
}: {
  title: string
  withLabel?: boolean
  onClose: () => void
  onSubmit: (label: string, cookies: string) => Promise<void>
}) {
  const [label, setLabel] = useState('')
  const [text, setText] = useState('')
  const [fileName, setFileName] = useState<string | null>(null)
  const [drag, setDrag] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const readFile = async (f: File | undefined) => {
    if (!f) return
    if (f.size > 1024 * 1024) return setErr('Файл больше 1 МБ — это точно cookies?')
    setText(await f.text())
    setFileName(f.name)
    setErr(null)
    if (withLabel && !label.trim()) setLabel(f.name.replace(/\.(txt|json)$/i, '').slice(0, 60))
  }

  const lines = useMemo(() => text.split('\n').filter((l) => l.includes('youtube.com')).length, [text])

  return (
    <Dialog title={title} onClose={onClose} width={560}>
      <form
        className="adm-form"
        onSubmit={async (e) => {
          e.preventDefault()
          setSaving(true)
          setErr(null)
          try {
            await onSubmit(label.trim(), text)
          } catch (e2) {
            setErr((e2 as Error).message)
          }
          setSaving(false)
        }}
      >
        {withLabel && (
          <label className="field">
            <span>Название — чтобы отличать аккаунты</span>
            <input className="input" value={label} maxLength={60} onChange={(e) => setLabel(e.target.value)} placeholder="например, music-bot-1@gmail.com" />
          </label>
        )}
        <div
          className={`adm-drop ${drag ? 'drag' : ''} ${fileName ? 'has' : ''}`}
          onDragOver={(e) => {
            e.preventDefault()
            setDrag(true)
          }}
          onDragLeave={() => setDrag(false)}
          onDrop={(e) => {
            e.preventDefault()
            setDrag(false)
            void readFile(e.dataTransfer.files[0])
          }}
          onClick={() => input.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current?.click()}
        >
          <FileUp size={26} />
          {fileName ? (
            <span>
              <b>{fileName}</b> · строк с youtube.com: {lines}
            </span>
          ) : (
            <span>
              Перетащите сюда <b>cookies.txt</b> или JSON, или нажмите, чтобы выбрать файл
            </span>
          )}
          <input ref={input} type="file" accept=".txt,.json,text/plain,application/json" hidden onChange={(e) => void readFile(e.target.files?.[0])} />
        </div>
        <details className="adm-paste">
          <summary>или вставить текстом</summary>
          <textarea
            className="input adm-textarea"
            value={text}
            onChange={(e) => {
              setText(e.target.value)
              setFileName(null)
            }}
            placeholder={'# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t…'}
            spellCheck={false}
          />
        </details>
        {err && <p className="adm-error">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Отмена
          </button>
          <button className="btn btn-primary" disabled={saving || !text.trim() || (withLabel && !label.trim())}>
            {saving ? 'Сохраняю…' : 'Сохранить'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}

// ───────── журнал загрузок ─────────

const FALLBACK_NAMES: Record<string, string> = { audius: 'Audius', soundcloud: 'SoundCloud', archive: 'archive.org' }

function clientLabel(e: DlEvent) {
  if (e.client === 'test') return `проверка · ${e.accountLabel ?? `#${e.account}`}`
  if (e.client.startsWith('fallback')) {
    const src = e.client.split(':')[1]
    return src ? `запасной источник · ${FALLBACK_NAMES[src] ?? src}` : 'запасные источники'
  }
  if (e.account != null) return e.accountLabel ?? `аккаунт #${e.account}`
  return `без аккаунта · ${e.client}`
}

function Downloads() {
  const [fail, setFail] = useState(false)
  const [video, setVideo] = useState('')
  const [applied, setApplied] = useState('')
  const [live, setLive] = useState(true)
  const { data, error, busy, reload } = usePoll<DlEvent[]>(
    () => adminApi.events({ limit: 300, fail, video: applied || undefined }),
    live ? 10000 : null,
    [fail, applied],
  )

  return (
    <div className="adm-stack">
      <div className="adm-bar-row">
        <div className="seg" role="tablist">
          <button role="tab" aria-selected={!fail} onClick={() => setFail(false)}>
            Все попытки
          </button>
          <button role="tab" aria-selected={fail} onClick={() => setFail(true)}>
            Только ошибки
          </button>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            setApplied(video.trim())
          }}
          style={{ display: 'flex', gap: 8, flex: 1, minWidth: 200 }}
        >
          <input className="input adm-input-sm" value={video} onChange={(e) => setVideo(e.target.value)} placeholder="ID трека, например dQw4w9WgXcQ" />
        </form>
        <label className="adm-check">
          <input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> обновлять
        </label>
        <RefreshBtn busy={busy} onClick={reload} />
      </div>
      <p className="small faint">
        Каждая попытка скачать трек с YouTube: каким способом, сколько заняла и чем кончилась. Треки из кэша сюда не попадают — они не качаются заново.
      </p>
      {error && <p className="adm-error">{error}</p>}
      {data && data.length === 0 && <div className="empty">Попыток пока нет.</div>}
      {data && data.length > 0 && (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead>
              <tr>
                <th>Время</th>
                <th>Трек</th>
                <th>Способ</th>
                <th>Итог</th>
                <th className="r">Время</th>
              </tr>
            </thead>
            <tbody>
              {data.map((e) => (
                <tr key={e.id} className={e.ok ? '' : 'fail'}>
                  <td className="nowrap faint">{fmtDateTime(e.at)}</td>
                  <td className="track">
                    <a href={`https://music.youtube.com/watch?v=${e.video}`} target="_blank" rel="noreferrer" className="hover-link">
                      {e.title ?? <span className="mono">{e.video}</span>}
                    </a>
                    {!e.ok && e.err && <div className="err" title={e.err}>{shortErr(e.err)}</div>}
                  </td>
                  <td className="nowrap">{clientLabel(e)}</td>
                  <td className="nowrap">
                    {e.ok ? <span className="adm-badge ok">скачан</span> : <span className="adm-badge bad">{KIND[e.kind ?? 'other'] ?? e.kind}</span>}
                  </td>
                  <td className="r nowrap faint">{(e.ms / 1000).toFixed(1)} с</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ───────── логи ─────────

const SOURCES = [
  { id: 'music', label: 'Сервис музыки', hint: 'journalctl -u music: загрузки, ошибки yt-dlp, письма, очистка кэша' },
  { id: 'caddy', label: 'Caddy', hint: 'веб-сервер: сертификаты, ошибки проксирования' },
  { id: 'bgutil', label: 'PO-токены', hint: 'контейнер bgutil-provider' },
  { id: 'ytdlp', label: 'Обновления yt-dlp', hint: '/var/log/music-ytdlp.log, каждую ночь в 04:15' },
  { id: 'backup', label: 'Бэкапы', hint: '/var/log/music-backup.log, каждую ночь в 04:25' },
  { id: 'system', label: 'Система', hint: 'предупреждения и ошибки всего сервера (journalctl -p warning)' },
]

function lineTone(l: string) {
  const s = l.toLowerCase()
  if (/\berror\b|failed|panic|fatal|ошибк|не удалось|"level":"error"/.test(s)) return 'bad'
  if (/\bwarn|cooldown|dead|"level":"warn"/.test(s)) return 'warn'
  return ''
}

function Logs() {
  const [src, setSrc] = useState('music')
  const [lines, setLines] = useState(300)
  const [q, setQ] = useState('')
  const [applied, setApplied] = useState('')
  const [live, setLive] = useState(false)
  const box = useRef<HTMLPreElement>(null)
  const stick = useRef(true)
  const { data, error, busy, reload } = usePoll(() => adminApi.logs({ src, lines, q: applied || undefined }), live ? 5000 : null, [src, lines, applied])

  useEffect(() => {
    const el = box.current
    if (el && stick.current) el.scrollTop = el.scrollHeight
  }, [data])

  const hint = SOURCES.find((s) => s.id === src)?.hint

  return (
    <div className="adm-stack">
      <div className="adm-chips">
        {SOURCES.map((s) => (
          <button key={s.id} className={`chip ${src === s.id ? 'on' : ''}`} aria-pressed={src === s.id} onClick={() => setSrc(s.id)}>
            {s.label}
          </button>
        ))}
      </div>
      <div className="adm-bar-row">
        <form
          onSubmit={(e) => {
            e.preventDefault()
            setApplied(q.trim())
          }}
          style={{ display: 'flex', gap: 8, flex: 1, minWidth: 200 }}
        >
          <input className="input adm-input-sm" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Фильтр: error, bot, ID трека… (Enter)" />
        </form>
        <button
          className={`chip ${applied === 'error' ? 'on' : ''}`}
          onClick={() => {
            const v = applied === 'error' ? '' : 'error'
            setQ(v)
            setApplied(v)
          }}
        >
          только error
        </button>
        <select className="input adm-input-sm adm-select" value={lines} onChange={(e) => setLines(Number(e.target.value))} aria-label="Сколько строк">
          {[100, 300, 1000, 3000].map((n) => (
            <option key={n} value={n}>
              {n} строк
            </option>
          ))}
        </select>
        <label className="adm-check">
          <input type="checkbox" checked={live} onChange={(e) => setLive(e.target.checked)} /> в реальном времени
        </label>
        <RefreshBtn busy={busy} onClick={reload} />
      </div>
      {hint && <p className="small faint">{hint}</p>}
      {error && <p className="adm-error">{error}</p>}
      {data?.error && <p className="adm-error">{data.error}</p>}
      <pre
        ref={box}
        className="adm-log"
        onScroll={(e) => {
          const el = e.currentTarget
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40
        }}
      >
        {data?.lines.length === 0 && <span className="faint">Пусто{applied ? ' — ничего не нашлось по фильтру' : ''}.</span>}
        {data?.lines.map((l, i) => (
          <div key={i} className={lineTone(l)}>
            {l}
          </div>
        ))}
      </pre>
    </div>
  )
}

// ───────── пользователи ─────────

function UsersTab() {
  const { data, error, busy, reload } = usePoll<AdminUser[]>(adminApi.users, null)
  return (
    <div className="adm-stack">
      <div className="adm-bar-row">
        <p className="muted" style={{ flex: 1 }}>
          {data ? `${data.length} ${plural(data.length, 'пользователь', 'пользователя', 'пользователей')}` : ''}
        </p>
        <RefreshBtn busy={busy} onClick={reload} />
      </div>
      {error && <p className="adm-error">{error}</p>}
      {data && (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead>
              <tr>
                <th>Почта</th>
                <th>Имя</th>
                <th>Регистрация</th>
                <th>Заходил</th>
                <th>Последний трек</th>
                <th className="r">Прослушиваний</th>
                <th className="r">Лайков</th>
                <th className="r">Плейлистов</th>
                <th className="r">Устройств</th>
              </tr>
            </thead>
            <tbody>
              {data.map((u) => (
                <tr key={u.id}>
                  <td className="nowrap">
                    {u.email} {u.admin && <span className="adm-badge info">админ</span>}{' '}
                    {!u.verified && <span className="adm-badge muted">почта не подтверждена</span>}
                  </td>
                  <td>{u.name || <span className="faint">—</span>}</td>
                  <td className="nowrap faint">{new Date(u.created * 1000).toLocaleDateString('ru-RU')}</td>
                  <td className="nowrap">{agoOr(u.lastSeen)}</td>
                  <td className="nowrap">{agoOr(u.lastPlay)}</td>
                  <td className="r">{u.plays}</td>
                  <td className="r">{u.likes}</td>
                  <td className="r">{u.playlists}</td>
                  <td className="r">{u.sessions}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
