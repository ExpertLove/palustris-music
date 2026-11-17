// Выравнивание громкости: у каждого трека свой множитель по его громкости (EBU R128).
// Громкость считает сервер после загрузки трека (POST /api/loudness), здесь значения кэшируются
// в памяти и в localStorage. Формула та же, что в Android-приложении (LoudnessMath).
import { api } from '../lib/api'

export interface Loudness {
  lufs: number
  peak: number
}

/** Как у Spotify и YouTube. */
const TARGET_LUFS = -14
/** Тихие треки поднимаем не больше чем на 6 дБ и так, чтобы пик остался ниже −1 dBTP: без хрипа. */
const MAX_BOOST_DB = 6
const PEAK_CEILING_DB = -1
/** Громкость ещё не посчитана: современные треки обычно около −9 LUFS, так что −5 дБ близко к правде. */
const UNKNOWN_DB = -5

const KEY = 'music.loudness.v1'
const MAX_KEEP = 3000

export function gainDb(l: Loudness | undefined): number {
  // Значение вне разумных пределов — испорченная запись, а не «очень громкий трек».
  if (!l || !Number.isFinite(l.lufs) || l.lufs < -60 || l.lufs > 0 || !Number.isFinite(l.peak) || l.peak < -60 || l.peak > 6) {
    return UNKNOWN_DB
  }
  let g = TARGET_LUFS - l.lufs
  if (g > 0) g = Math.max(0, Math.min(g, MAX_BOOST_DB, PEAK_CEILING_DB - l.peak))
  return Math.max(-20, Math.min(MAX_BOOST_DB, g))
}

const known = new Map<string, Loudness>()
const nextTry = new Map<string, number>()
const tries = new Map<string, number>()

try {
  const saved = JSON.parse(localStorage.getItem(KEY) || '{}') as Record<string, [number, number]>
  for (const [id, [lufs, peak]] of Object.entries(saved)) known.set(id, { lufs, peak })
} catch {
  /* испорченный кэш — начнём с нуля */
}

let saveTimer = 0
function save() {
  if (saveTimer) return
  saveTimer = window.setTimeout(() => {
    saveTimer = 0
    // Map помнит порядок добавления: храним самые свежие.
    const all = [...known.entries()].slice(-MAX_KEEP)
    try {
      localStorage.setItem(KEY, JSON.stringify(Object.fromEntries(all.map(([id, l]) => [id, [l.lufs, l.peak]]))))
    } catch {
      /* переполнено */
    }
  }, 2000)
}

/** Множитель громкости трека (линейный). */
export function levelOf(id: string): number {
  return Math.pow(10, gainDb(known.get(id)) / 20)
}

/**
 * Спросить громкость треков, которых ещё нет. Можно звать часто: известные и недавно
 * спрошенные пропускаются (повтор через 4, 8, 16 с… до 10 минут — пока сервер скачивает трек).
 */
export function requestLoudness(ids: string[], onUpdate: () => void) {
  const now = Date.now()
  const want = [...new Set(ids)].filter((id) => id && !known.has(id) && (nextTry.get(id) ?? 0) <= now).slice(0, 100)
  if (!want.length) return
  for (const id of want) {
    const n = (tries.get(id) ?? 0) + 1
    tries.set(id, n)
    nextTry.set(id, now + Math.min(600_000, 4000 * 2 ** Math.min(n - 1, 8)))
  }
  api
    .loudness(want)
    .then((got) => {
      const entries = Object.entries(got || {})
      if (!entries.length) return
      for (const [id, l] of entries) {
        known.delete(id)
        known.set(id, l)
        tries.delete(id)
        nextTry.delete(id)
      }
      save()
      onUpdate()
    })
    .catch(() => null)
}
