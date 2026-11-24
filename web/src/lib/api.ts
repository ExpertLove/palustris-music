import type {
  AccountTest,
  AdminOverview,
  AdminUser,
  ArtistPage,
  DlEvent,
  Collection,
  Dislikes,
  HistItem,
  ImportPreview,
  LibraryArtist,
  Lyrics,
  Mixes,
  PlaylistFull,
  PlaylistRow,
  Profile,
  SearchAll,
  SharedPlaylist,
  Stats,
  StatsPeriod,
  Substitute,
  Track,
  WaveSettings,
  Wrapped,
  YtAccount,
} from './types'

const TOKEN_KEY = 'music.token'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

let onUnauthorized: () => void = () => {}
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn
}

export const token = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

/** Тексты ошибок сервера бывают техническими — переводим самые частые. */
function humanize(status: number, text: string): string {
  const t = text.trim()
  if (status === 401) return 'Нужно войти заново'
  if (status === 429) return t || 'Слишком много попыток, подожди немного'
  if (status >= 500 && !t) return 'Сервер не ответил, попробуй ещё раз'
  if (t === 'bad email') return 'Проверь email'
  return t || `Ошибка ${status}`
}

async function req<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const t = token.get()
  if (t) headers.set('Authorization', `Bearer ${t}`)
  let body = init.body
  if (init.json !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(init.json)
  }
  let r: Response
  try {
    r = await fetch(`/api${path}`, { ...init, headers, body, credentials: 'same-origin' })
  } catch {
    throw new ApiError(0, 'Нет связи с сервером')
  }
  if (r.status === 401 && t && !path.startsWith('/login')) onUnauthorized()
  if (!r.ok) throw new ApiError(r.status, humanize(r.status, await r.text().catch(() => '')))
  const text = await r.text()
  return (text ? JSON.parse(text) : null) as T
}

const get = <T>(p: string) => req<T>(p)
const post = <T>(p: string, json?: unknown) => req<T>(p, { method: 'POST', json: json ?? {} })
const del = <T>(p: string, json?: unknown) => req<T>(p, { method: 'DELETE', json })

/** Сдвиг местного времени от UTC в минутах (Москва = 180): сервер делит статистику на дни и часы по нему. */
export const tzMinutes = () => -new Date().getTimezoneOffset()
const q = (o: Record<string, string | number | undefined | null>) => {
  const s = new URLSearchParams()
  for (const [k, v] of Object.entries(o)) if (v !== undefined && v !== null && v !== '') s.set(k, String(v))
  return s.toString()
}

export interface AuthResp {
  token: string
  email: string
  verifyUrl?: string | null
}

export const api = {
  // аккаунт
  me: () => get<Profile>('/me'),
  login: (email: string, password: string) => post<AuthResp>('/login', { email, password }),
  register: (b: { email: string; password: string; name: string; genres: string[]; consent: boolean }) =>
    post<AuthResp>('/register', b),
  logout: () => post<null>('/logout'),
  logoutOthers: () => post<{ removed: number }>('/logout-others'),
  forgot: (email: string) => post<{ ok: boolean }>('/password/forgot', { email }),
  reset: (tok: string, password: string) => post<AuthResp>('/password/reset', { token: tok, password }),
  changePassword: (old: string, nw: string) => post<{ ok: boolean; loggedOut: number }>('/password/change', { old, new: nw }),
  resend: () => post<{ verified: boolean; letterSent?: boolean; verifyUrl?: string | null }>('/resend'),
  deleteAccount: () => del<null>('/account'),
  setProfile: (name: string, genres: string[]) => post<null>('/profile', { name, genres }),

  // каталог
  searchTracks: (s: string) => get<Track[]>(`/search?${q({ q: s })}`),
  searchAll: (s: string) => get<SearchAll>(`/search/all?${q({ q: s })}`),
  top: (region: string) => get<Track[]>(`/top?${q({ region })}`),
  recs: () => get<Track[]>('/recs'),
  wave: (w?: Partial<WaveSettings>, region?: string) =>
    get<Track[]>(`/wave?${q({ mode: w?.mode, mood: w?.mood, activity: w?.activity, region: w?.mode === 'popular' ? region : undefined })}`),
  /** Радио по треку: до 40 похожих, сам трек не входит. */
  radio: (t: Track) => get<Track[]>(`/radio?${q({ id: t.id, title: t.title, artist: t.artist })}`),
  artistRadio: (p: { artistId?: string | null; artistName?: string }) => get<Track[]>(`/radio?${q(p)}`),
  mixes: () => get<Mixes>('/mixes'),
  dislikes: () => get<Dislikes>('/dislikes'),
  /** Что играет вместо трека, если YouTube его не отдал (`null` — играет сам трек). */
  substitute: (id: string) => get<Substitute | null>(`/substitute/${encodeURIComponent(id)}`),
  dislike: (t: Track) => post<null>('/dislikes', strip(t)),
  undislike: (id: string) => del<null>(`/dislikes/${encodeURIComponent(id)}`),
  blockArtist: (name: string) => post<null>('/blocked-artists', { name }),
  unblockArtist: (name: string) => del<null>('/blocked-artists', { name }),
  stats: (period: StatsPeriod) => get<Stats>(`/stats?${q({ period, tz: tzMinutes() })}`),
  wrapped: (year?: number) => get<Wrapped>(`/wrapped?${q({ year, tz: tzMinutes() })}`),
  artists: () => get<LibraryArtist[]>('/artists'),
  artist: (p: { id?: string; name?: string }) => get<ArtistPage>(`/yt/artist?${q(p)}`),
  collection: (id: string) => get<Collection>(`/yt/collection/${encodeURIComponent(id)}`),
  lyrics: (t: Track, duration: number) =>
    get<Lyrics>(`/lyrics?${q({ artist: t.artist, title: t.title, duration: Math.round(duration || t.duration || 0) })}`),

  // библиотека
  likes: () => get<Track[]>('/likes'),
  like: (t: Track) => post<null>('/likes', strip(t)),
  unlike: (id: string) => del<null>(`/likes/${encodeURIComponent(id)}`),
  history: (limit = 50) => get<HistItem[]>(`/history?limit=${limit}`),
  /** Прослушивание: когда начало играть (`at`, unix) и сколько секунд звучало (`secs`) — для статистики и рекомендаций.
   *  `keepalive` — чтобы запрос ушёл, даже если вкладку закрывают. */
  addHistory: (t: Track, at?: number, secs?: number, keepalive = false) =>
    req<null>('/history', { method: 'POST', json: { ...strip(t), at, secs }, keepalive }),
  clearHistory: () => del<null>('/history'),
  skip: (t: Track) => post<null>('/skip', strip(t)),
  prefetch: (ids: string[]) => post<null>('/prefetch', { ids }),
  /** Громкость треков (LUFS и пик); неизвестных серверу в ответе нет. */
  loudness: (ids: string[]) => post<Record<string, { lufs: number; peak: number }>>('/loudness', { ids }),

  playlists: () => get<PlaylistRow[]>('/playlists'),
  importPreview: (b: { link?: string; text?: string }) => post<ImportPreview>('/import/preview', b),
  playlist: (id: number) => get<PlaylistFull>(`/playlists/${id}`),
  createPlaylist: (name: string, items: Track[] = []) =>
    post<{ id: number }>('/playlists', { name, items: items.map(strip) }),
  renamePlaylist: (id: number, name: string) => req<null>(`/playlists/${id}`, { method: 'PATCH', json: { name } }),
  deletePlaylist: (id: number) => del<null>(`/playlists/${id}`),
  addToPlaylist: (id: number, t: Track) => post<null>(`/playlists/${id}/items`, strip(t)),
  removeFromPlaylist: (id: number, trackId: string) =>
    del<null>(`/playlists/${id}/items/${encodeURIComponent(trackId)}`),
  reorderPlaylist: (id: number, ids: string[]) => req<null>(`/playlists/${id}/order`, { method: 'PUT', json: { ids } }),
  sharePlaylist: (id: number) => post<{ share: string }>(`/playlists/${id}/share`),
  unsharePlaylist: (id: number) => del<null>(`/playlists/${id}/share`),
  shared: (slug: string) => get<SharedPlaylist>(`/shared/${encodeURIComponent(slug)}`),
}

export const adminApi = {
  overview: () => get<AdminOverview>('/admin/overview'),
  accounts: () => get<YtAccount[]>('/admin/accounts'),
  addAccount: (label: string, cookies: string) => post<{ id: number; cookies: number }>('/admin/accounts', { label, cookies }),
  replaceCookies: (id: number, cookies: string) =>
    req<{ cookies: number }>(`/admin/accounts/${id}/cookies`, { method: 'PUT', json: { cookies } }),
  patchAccount: (id: number, b: { label?: string; enabled?: boolean; reset?: boolean }) =>
    req<null>(`/admin/accounts/${id}`, { method: 'PATCH', json: b }),
  deleteAccount: (id: number) => del<null>(`/admin/accounts/${id}`),
  testAccount: (id: number) => post<AccountTest>(`/admin/accounts/${id}/test`),
  events: (p: { limit?: number; fail?: boolean; video?: string }) =>
    get<DlEvent[]>(`/admin/events?${q({ limit: p.limit, fail: p.fail ? 1 : undefined, video: p.video })}`),
  logs: (p: { src: string; lines: number; q?: string }) =>
    get<{ src: string; lines: string[]; error?: string }>(`/admin/logs?${q(p)}`),
  users: () => get<AdminUser[]>('/admin/users'),
}

/** На сервер уходят только поля трека, которые он хранит. */
function strip(t: Track): Track {
  return {
    id: t.id,
    title: t.title,
    artist: t.artist,
    album: t.album ?? null,
    duration: Math.round(t.duration || 0),
    thumb: t.thumb ?? null,
  }
}

/** Исполнитель, название и длительность — чтобы сервер нашёл такую же запись, если YouTube не отдаст трек. */
export const streamUrl = (t: Track) =>
  `/api/stream/${encodeURIComponent(t.id)}?${q({ a: t.artist, t: t.title, d: t.duration > 0 ? Math.round(t.duration) : null })}`
