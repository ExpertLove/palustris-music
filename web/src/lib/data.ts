import {
  AudioWaveform,
  Car,
  CloudRain,
  Clapperboard,
  Disc3,
  Flame,
  Flower2,
  Guitar,
  Headphones,
  Heart,
  Leaf,
  Mic,
  Moon,
  Music2,
  Music4,
  PartyPopper,
  Piano,
  Radio,
  Snowflake,
  Sparkles,
  Star,
  Sun,
  Sunrise,
  Target,
  Tent,
  TreePalm,
  Waves,
  Zap,
  type LucideIcon,
} from 'lucide-react'

export interface Mood {
  id: string
  title: string
  sub: string
  icon: LucideIcon
  q: string
  from: string
  to: string
}

export const MOODS: Mood[] = [
  { id: 'night', title: 'Ночная', sub: 'после полуночи', icon: Moon, q: 'night drive music', from: '#3b3a9e', to: '#101326' },
  { id: 'morning', title: 'Утренняя', sub: 'мягкий старт дня', icon: Sunrise, q: 'morning music playlist', from: '#e0812f', to: '#2a1d14' },
  { id: 'energy', title: 'Энергия', sub: 'заряд на весь день', icon: Zap, q: 'workout energy music', from: '#c3299f', to: '#24113a' },
  { id: 'calm', title: 'Спокойствие', sub: 'тишина внутри', icon: Waves, q: 'calm relaxing music', from: '#1690a8', to: '#0b2532' },
  { id: 'sad', title: 'Грусть', sub: 'побыть одному', icon: CloudRain, q: 'sad songs', from: '#55607a', to: '#15171f' },
  { id: 'love', title: 'Любовь', sub: 'о главном', icon: Heart, q: 'love songs', from: '#d8336f', to: '#2b0f2a' },
  { id: 'party', title: 'Вечеринка', sub: 'громко и вместе', icon: PartyPopper, q: 'party hits', from: '#e8562b', to: '#3a1450' },
  { id: 'focus', title: 'Фокус', sub: 'работа без отвлечений', icon: Target, q: 'focus study music', from: '#139184', to: '#0d1d24' },
  { id: 'retro', title: 'Ретро', sub: 'хиты прошлых лет', icon: Radio, q: '80s hits', from: '#c08a1c', to: '#2b2318' },
  { id: 'russian', title: 'Русское', sub: 'родные хиты', icon: Music2, q: 'русские хиты', from: '#2f62e6', to: '#151a3d' },
  { id: 'rap', title: 'Рэп', sub: 'биты и панчи', icon: Mic, q: 'rap hits', from: '#6b7487', to: '#14161c' },
  { id: 'rock', title: 'Рок', sub: 'гитары на максимум', icon: Guitar, q: 'rock hits', from: '#d0322f', to: '#22140f' },
  { id: 'electronic', title: 'Электроника', sub: 'синтез и ритм', icon: AudioWaveform, q: 'electronic dance music', from: '#7b47e8', to: '#120d29' },
  { id: 'indie', title: 'Инди', sub: 'не как у всех', icon: Headphones, q: 'indie music', from: '#2c9a52', to: '#0b2214' },
  { id: 'jazz', title: 'Джаз', sub: 'вечерний лаунж', icon: Music4, q: 'jazz music', from: '#a8561a', to: '#21170f' },
  { id: 'classical', title: 'Классика', sub: 'вне времени', icon: Piano, q: 'classical music', from: '#8c3fd6', to: '#1b1438' },
  { id: 'summer', title: 'Летняя', sub: 'солнце и море', icon: Sun, q: 'summer hits', from: '#f07a26', to: '#0f5d70' },
  { id: 'winter', title: 'Зимняя', sub: 'снег за окном', icon: Snowflake, q: 'winter playlist', from: '#2aa2e0', to: '#111d33' },
  { id: 'road', title: 'Дорога', sub: 'для долгой поездки', icon: Car, q: 'road trip music', from: '#5150d8', to: '#121633' },
  { id: 'romance', title: 'Романтика', sub: 'для двоих', icon: Flower2, q: 'romantic songs', from: '#b8285f', to: '#21122e' },
]

export interface Genre {
  id: string
  title: string
  icon: LucideIcon
}

export const GENRES: Genre[] = [
  { id: 'pop', title: 'Поп', icon: Sparkles },
  { id: 'rock', title: 'Рок', icon: Guitar },
  { id: 'rap', title: 'Рэп', icon: Mic },
  { id: 'electronic', title: 'Электроника', icon: AudioWaveform },
  { id: 'indie', title: 'Инди', icon: Leaf },
  { id: 'jazz', title: 'Джаз', icon: Music4 },
  { id: 'classical', title: 'Классика', icon: Piano },
  { id: 'metal', title: 'Метал', icon: Flame },
  { id: 'punk', title: 'Панк', icon: Zap },
  { id: 'rnb', title: 'R&B и соул', icon: Disc3 },
  { id: 'reggae', title: 'Регги', icon: TreePalm },
  { id: 'folk', title: 'Фолк', icon: Tent },
  { id: 'russian', title: 'Русское', icon: Music2 },
  { id: 'kpop', title: 'K-pop', icon: Star },
  { id: 'soundtrack', title: 'Саундтреки', icon: Clapperboard },
]

export const REGIONS: { code: string; title: string }[] = [
  { code: '', title: 'Весь мир' },
  { code: 'RU', title: 'Россия' },
  { code: 'BY', title: 'Беларусь' },
  { code: 'KZ', title: 'Казахстан' },
  { code: 'UA', title: 'Украина' },
  { code: 'UZ', title: 'Узбекистан' },
  { code: 'GE', title: 'Грузия' },
  { code: 'AM', title: 'Армения' },
  { code: 'DE', title: 'Германия' },
  { code: 'US', title: 'США' },
  { code: 'GB', title: 'Великобритания' },
  { code: 'FR', title: 'Франция' },
  { code: 'ES', title: 'Испания' },
  { code: 'IT', title: 'Италия' },
  { code: 'PL', title: 'Польша' },
  { code: 'TR', title: 'Турция' },
  { code: 'AE', title: 'ОАЭ' },
  { code: 'IL', title: 'Израиль' },
  { code: 'IN', title: 'Индия' },
  { code: 'KR', title: 'Южная Корея' },
  { code: 'JP', title: 'Япония' },
  { code: 'BR', title: 'Бразилия' },
  { code: 'CA', title: 'Канада' },
  { code: 'AU', title: 'Австралия' },
]

const REGION_KEY = 'music.region'
export function getRegion(): string {
  const saved = localStorage.getItem(REGION_KEY)
  if (saved !== null) return saved
  const lang = ((navigator.languages?.[0] || navigator.language || '').split('-')[1] || '').toUpperCase()
  return REGIONS.some((r) => r.code === lang) ? lang : 'RU'
}
export const setRegion = (c: string) => localStorage.setItem(REGION_KEY, c)
export const regionTitle = (c: string) => REGIONS.find((r) => r.code === c)?.title ?? 'Весь мир'

/** Полосы эквалайзера, Гц. */
export const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
export const EQ_LABELS = ['31', '62', '125', '250', '500', '1к', '2к', '4к', '8к', '16к']
export const EQ_MAX = 12

export interface EqPreset {
  id: string
  title: string
  gains: number[]
}

export const EQ_PRESETS: EqPreset[] = [
  { id: 'flat', title: 'Ровно', gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
  { id: 'bass', title: 'Бас', gains: [6, 5, 4, 2, 0, 0, 0, 0, 0, 0] },
  { id: 'bass-max', title: 'Много баса', gains: [9, 8, 6, 3, 1, 0, 0, 0, 0, 0] },
  { id: 'rock', title: 'Рок', gains: [5, 4, 3, 1, -1, -1, 1, 3, 4, 5] },
  { id: 'pop', title: 'Поп', gains: [-1, 0, 2, 4, 4, 2, 0, -1, -1, -1] },
  { id: 'electronic', title: 'Электроника', gains: [5, 4, 1, 0, -2, 2, 1, 2, 4, 5] },
  { id: 'hiphop', title: 'Хип-хоп', gains: [5, 4, 1, 3, -1, -1, 1, -1, 2, 3] },
  { id: 'vocal', title: 'Голос', gains: [-2, -2, -1, 1, 3, 4, 3, 1, 0, -1] },
  { id: 'jazz', title: 'Джаз', gains: [3, 2, 1, 2, -1, -1, 0, 1, 2, 3] },
  { id: 'classical', title: 'Классика', gains: [4, 3, 2, 1, -1, -1, 0, 2, 3, 4] },
  { id: 'acoustic', title: 'Акустика', gains: [4, 3, 2, 1, 2, 2, 3, 3, 3, 2] },
  { id: 'treble', title: 'Высокие', gains: [0, 0, 0, 0, 0, 0, 2, 4, 6, 7] },
  { id: 'night', title: 'Тихо ночью', gains: [5, 4, 2, 0, -1, 0, -1, -2, 3, 1] },
]
