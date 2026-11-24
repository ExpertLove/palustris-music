export interface Track {
  id: string
  title: string
  artist: string
  album?: string | null
  duration: number
  thumb?: string | null
  artistId?: string
  albumId?: string
  /** Почему трек в «Моей волне». */
  why?: string
}

/** Та же запись из другого каталога, когда YouTube не отдал трек (см. server/src/sources.rs). */
export interface Substitute {
  source: 'audius' | 'soundcloud' | 'archive'
  sourceName: string
  title: string
  artist: string
  duration: number
  /** Страница записи у источника. */
  page: string
}

export interface HistItem extends Track {
  playedAt: number
  plays: number
}

export interface Profile {
  admin?: boolean
  email: string
  name: string
  genres: string[]
  created: number
  likes: number
  playlists: number
  plays: number
  sessions: number
  verified: boolean
  consentAt: number
}

export interface PlaylistRow {
  id: number
  name: string
  count: number
  thumbs: string[]
  share: string | null
}

export interface PlaylistFull {
  id: number
  name: string
  share: string | null
  items: Track[]
}

export interface ArtistCard {
  id: string
  name: string
  thumb: string | null
  sub: string
}

export interface AlbumCard {
  id: string
  title: string
  artist: string
  sub: string
  thumb: string | null
  kind: 'album' | 'single' | 'playlist'
}

export interface SearchAll {
  top: ArtistCard | null
  tracks: Track[]
  artists: ArtistCard[]
  albums: AlbumCard[]
  playlists: AlbumCard[]
}

export interface ArtistPage {
  id: string
  name: string
  thumb: string | null
  listeners: string
  description: string
  tracks: Track[]
  allTracks: string | null
  albums: AlbumCard[]
  singles: AlbumCard[]
  playlists: AlbumCard[]
  similar: ArtistCard[]
}

export interface Collection {
  id: string
  kind: 'album' | 'single' | 'playlist'
  title: string
  artist: string
  artistId: string | null
  sub: string
  thumb: string | null
  description: string
  tracks: Track[]
}

export interface LibraryArtist {
  name: string
  count: number
  thumb: string | null
}

export interface Lyrics {
  synced: string | null
  plain: string | null
  instrumental: boolean
  found: boolean
}

export interface SharedPlaylist {
  name: string
  owner: string
  share: string
  items: Track[]
}

// ───────── рекомендации ─────────

/** Настройки «Моей волны»: пустая строка — «любое». Значения — те, что понимает `/api/wave`. */
export interface WaveSettings {
  mode: '' | 'favorite' | 'discover' | 'popular'
  mood: '' | 'energetic' | 'happy' | 'calm' | 'sad'
  activity: '' | 'wakeup' | 'road' | 'work' | 'workout' | 'sleep' | 'party'
}

export interface Mix {
  id: string
  title: string
  sub: string
  thumbs: string[]
  tracks: Track[]
}

export interface Mixes {
  mixes: Mix[]
  /** Лайкнутые давно и давно не игравшие. */
  forgotten: Track[]
  /** Что крутят последние недели. */
  repeat: Track[]
}

export interface Dislikes {
  tracks: Track[]
  artists: { name: string; at: number }[]
}

// ───────── статистика ─────────

export interface ArtistStat {
  name: string
  plays: number
  minutes: number
  thumb: string | null
  /** Фото из YouTube Music, только у первых мест. */
  image: string | null
  artistId?: string
}

export interface TrackStat extends Track {
  plays: number
  minutes: number
}

export type StatsPeriod = 'week' | 'month' | 'year' | 'all'

export interface Stats {
  period: StatsPeriod
  from: number
  minutes: number
  plays: number
  tracks: number
  artists: number
  prevMinutes: number | null
  topArtists: ArtistStat[]
  topTracks: TrackStat[]
  /** Минуты по часам суток, 24 значения. */
  hours: number[]
  /** Минуты по дням недели, с понедельника. */
  weekdays: number[]
  buckets: { start: number; minutes: number }[]
  bucketKind: 'day' | 'month'
  streak: number
  bestStreak: number
  newArtists: number
  since: number
}

export interface Wrapped {
  year: number
  minutes: number
  plays: number
  tracks: number
  artists: number
  days: number
  topArtists: ArtistStat[]
  topTracks: TrackStat[]
  /** Минуты по месяцам, 12 значений. */
  months: number[]
  hours: number[]
  busiestDay: { start: number; minutes: number; plays: number } | null
  bestStreak: number
  newArtists: number
  firstTrack: Track | null
  firstAt: number
  likes: number
  /** Какую долю слушателей обогнал по минутам, %. Нет, если слушателей мало. */
  percentile: number | null
}

// ───────── админка ─────────

export interface AdminOverview {
  now: number
  startedAt: number
  ipFlaggedUntil: number
  /** YouTube блокирует сервер с этого момента: новые треки идут из запасных источников. */
  ytBlockedSince: number | null
  ytNextProbe: number | null
  fallback: { hour: { ok: number; miss: number }; day: { ok: number; miss: number } }
  accounts: { total: number; available: number }
  hour: { ok: number; fail: number; attemptsFailed: number }
  day: { ok: number; fail: number; attemptsFailed: number }
  lastError: { at: number; err: string } | null
  cache: { files: number; bytes: number; maxBytes: number }
  users: number
  activeUsers: number
  playsDay: number
  ytdlp: string
  disk: { size: number; avail: number } | null
}

export type YtAccountStatus = 'ok' | 'new' | 'cooldown' | 'dead' | 'off'

export interface YtAccount {
  id: number
  label: string
  enabled: boolean
  status: YtAccountStatus
  note: string | null
  created: number
  updated: number
  lastUsed: number
  lastOk: number
  lastErr: number
  cooldownUntil: number
  okCount: number
  errCount: number
  hourOk: number
  cookies: number
  loggedIn: boolean
}

export interface AccountTest {
  ok: boolean
  loggedIn: boolean | null
  format?: string
  error?: string
  kind?: string
  ms: number
}

export interface DlEvent {
  id: number
  at: number
  video: string
  title: string | null
  account: number | null
  accountLabel: string | null
  client: string
  ok: boolean
  kind: string | null
  err: string | null
  ms: number
}

export interface AdminUser {
  id: number
  admin: boolean
  email: string
  name: string
  created: number
  verified: boolean
  lastSeen: number
  sessions: number
  plays: number
  likes: number
  playlists: number
  lastPlay: number
}

export interface ImportPreview {
  name: string
  total: number
  matched: Track[]
  missing: { artist: string; title: string }[]
}
