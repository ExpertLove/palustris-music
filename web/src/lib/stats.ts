// Подписи и расчёты для статистики и «Итогов года» — те же, что в приложении (ui/stats/Charts.kt).

export const MONTHS_SHORT = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']
export const MONTHS_FULL = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']
const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
export const WEEKDAYS = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс']

/** «Итоги года» показываются на главной в декабре и январе; в январе — за прошлый год. */
export function wrappedSeason(d = new Date()): number | null {
  if (d.getMonth() === 11) return d.getFullYear()
  if (d.getMonth() === 0) return d.getFullYear() - 1
  return null
}

/** За какой год показывать итоги сейчас: в январе ещё за прошлый. */
export const wrappedYear = (d = new Date()) => (d.getMonth() === 0 ? d.getFullYear() - 1 : d.getFullYear())

/** «1 234» с неразрывным пробелом. */
export const groupDigits = (n: number) => Math.round(n).toLocaleString('ru-RU').replace(/\s/g, '\u00a0')

/** «20 ч 34 мин» для больших значений, иначе «57 мин». */
export const minutesLong = (min: number) => (min < 120 ? `${min} мин` : `${Math.floor(min / 60)} ч ${min % 60} мин`)

/** Подпись столбика: день недели, число месяца или месяц. */
export function bucketLabel(start: number, kind: string, total: number, index: number): string {
  const d = new Date(start * 1000)
  if (kind === 'month') return total <= 12 || index % 3 === 0 ? MONTHS_SHORT[d.getMonth()] : ''
  if (total <= 7) return WEEKDAYS[(d.getDay() + 6) % 7]
  // Месяц по дням: подпись у каждого пятого, иначе не помещается.
  return index % 5 === 0 || index === total - 1 ? String(d.getDate()) : ''
}

/** Полная подпись для подсказки: «17 ноября» или «ноябрь 2026». */
export function bucketTitle(start: number, kind: string): string {
  const d = new Date(start * 1000)
  return kind === 'month' ? `${MONTHS_FULL[d.getMonth()]} ${d.getFullYear()}` : `${d.getDate()} ${MONTHS_GEN[d.getMonth()]}`
}

/** «17 ноября». */
export function dayTitle(unix: number): string {
  const d = new Date(unix * 1000)
  return `${d.getDate()} ${MONTHS_GEN[d.getMonth()]}`
}

/** Кто ты по времени прослушивания: по часам суток с самым большим числом минут. */
export interface Persona {
  title: string
  text: string
}

export function persona(hours: number[]): Persona | null {
  if (hours.length < 24 || !hours.some((h) => h > 0)) return null
  const sum = (a: number, b: number) => hours.slice(a, b + 1).reduce((x, y) => x + y, 0)
  const windows: [Persona, number][] = [
    [{ title: 'Ночная сова', text: 'Больше всего музыки — после полуночи' }, sum(0, 4)],
    [{ title: 'Жаворонок', text: 'Больше всего музыки — по утрам' }, sum(5, 10)],
    [{ title: 'Дневной слушатель', text: 'Больше всего музыки — днём' }, sum(11, 16)],
    [{ title: 'Вечерний меломан', text: 'Больше всего музыки — вечером' }, sum(17, 23)],
  ]
  return windows.reduce((a, b) => (b[1] > a[1] ? b : a))[0]
}
