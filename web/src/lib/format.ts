export function fmtTime(s: number): string {
  if (!Number.isFinite(s) || s < 0) s = 0
  s = Math.floor(s)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** 1 трек, 2 трека, 5 треков */
export function plural(n: number, one: string, few: string, many: string): string {
  const a = Math.abs(n) % 100
  const b = a % 10
  if (a > 10 && a < 20) return many
  if (b > 1 && b < 5) return few
  if (b === 1) return one
  return many
}

export const tracksWord = (n: number) => `${n} ${plural(n, 'трек', 'трека', 'треков')}`

export function totalDuration(sec: number): string {
  const m = Math.round(sec / 60)
  if (m < 60) return `${m} мин`
  const h = Math.floor(m / 60)
  const rest = m % 60
  return rest ? `${h} ч ${rest} мин` : `${h} ч`
}

export function greeting(d = new Date()): string {
  const h = d.getHours()
  if (h < 5) return 'Доброй ночи'
  if (h < 12) return 'Доброе утро'
  if (h < 18) return 'Добрый день'
  return 'Добрый вечер'
}

export function ago(ts: number): string {
  const s = Date.now() / 1000 - ts
  if (s < 90) return 'только что'
  if (s < 3600) return `${Math.round(s / 60)} мин назад`
  if (s < 86400) {
    const h = Math.round(s / 3600)
    return `${h} ${plural(h, 'час', 'часа', 'часов')} назад`
  }
  const d = Math.round(s / 86400)
  if (d === 1) return 'вчера'
  if (d < 7) return `${d} ${plural(d, 'день', 'дня', 'дней')} назад`
  return new Date(ts * 1000).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })
}

export const firstArtist = (a: string) => a.split(/,| & | и | feat\.? /i)[0].trim()

export function initials(s: string): string {
  return (
    s
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase())
      .join('') || '♪'
  )
}

export function shuffled<T>(a: T[]): T[] {
  const r = a.slice()
  for (let i = r.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[r[i], r[j]] = [r[j], r[i]]
  }
  return r
}

/** Обложка покрупнее: у googleusercontent размер задаётся суффиксом. */
export function bigThumb(u: string | null | undefined, px = 600): string | null {
  if (!u) return null
  if (/googleusercontent\.com|ggpht\.com/.test(u)) return u.replace(/=w\d+-h\d+[^&]*$|=s\d+[^&]*$/, `=w${px}-h${px}-l90-rj`)
  if (u.includes('i.ytimg.com/vi/')) return u.replace(/\/(hq|mq|sd)?default\.jpg.*$/, '/hqdefault.jpg')
  return u
}

/** Кадр клипа 4:3 с чёрными полосами — нужно обрезать в квадрат. */
export const isVideoThumb = (u: string | null | undefined) => !!u && u.includes('i.ytimg.com/vi/')
