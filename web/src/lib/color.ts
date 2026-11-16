// Цвета из обложки: акцент + два цвета «ауры» фона.
export interface Palette {
  accent: string
  accentInk: string
  aura1: string
  aura2: string
}

export const DEFAULT_PALETTE: Palette = {
  accent: '#ff5470',
  accentInk: '#16080b',
  aura1: '#ff5470',
  aura2: '#6d5dfc',
}

const cache = new Map<string, Promise<Palette>>()

type RGB = [number, number, number]

function rgbToHsl([r, g, b]: RGB): RGB {
  r /= 255
  g /= 255
  b /= 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h = 0
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0)
  else if (max === g) h = (b - r) / d + 2
  else h = (r - g) / d + 4
  return [h * 60, s, l]
}

function hslToHex(h: number, s: number, l: number): string {
  const k = (n: number) => (n + h / 30) % 12
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)))
  const to = (x: number) =>
    Math.round(x * 255)
      .toString(16)
      .padStart(2, '0')
  return `#${to(f(0))}${to(f(8))}${to(f(4))}`
}

export function luminance(hex: string): number {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
  const [r, g, b] = c.map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((res, rej) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.decoding = 'async'
    img.onload = () => res(img)
    img.onerror = rej
    img.src = src
  })
}

async function extract(src: string): Promise<Palette> {
  const img = await loadImage(src)
  const S = 48
  const cv = document.createElement('canvas')
  cv.width = S
  cv.height = S
  const g = cv.getContext('2d', { willReadFrequently: true })
  if (!g) return DEFAULT_PALETTE
  // Кадры клипов 4:3 с полосами — берём центр.
  const sw = img.naturalWidth
  const sh = img.naturalHeight
  const side = Math.min(sw, sh) * (src.includes('i.ytimg.com') ? 0.74 : 1)
  g.drawImage(img, (sw - side) / 2, (sh - side) / 2, side, side, 0, 0, S, S)
  const data = g.getImageData(0, 0, S, S).data

  // Корзины по оттенку (24 сектора) с весом = насыщенность × «видимость».
  const bins = Array.from({ length: 24 }, () => ({ w: 0, r: 0, g: 0, b: 0, n: 0 }))
  let grey = 0
  for (let i = 0; i < data.length; i += 4) {
    const rgb: RGB = [data[i], data[i + 1], data[i + 2]]
    const [h, s, l] = rgbToHsl(rgb)
    if (l < 0.08 || l > 0.95 || s < 0.15) {
      grey++
      continue
    }
    const w = s * (1 - Math.abs(l - 0.5) * 1.4)
    const bin = bins[Math.floor(h / 15) % 24]
    bin.w += w
    bin.r += rgb[0]
    bin.g += rgb[1]
    bin.b += rgb[2]
    bin.n++
  }
  const ranked = bins
    .map((b, i) => ({ ...b, i }))
    .filter((b) => b.n > 0)
    .sort((a, b) => b.w - a.w)

  // Почти ч/б обложка — спокойная нейтральная палитра.
  if (!ranked.length || ranked[0].n < S * S * 0.03) {
    void grey
    return { accent: '#e9e6df', accentInk: '#111214', aura1: '#8a8780', aura2: '#3b3d45' }
  }
  const avg = (b: (typeof ranked)[number]): RGB => [b.r / b.n, b.g / b.n, b.b / b.n]
  const [h1, s1] = rgbToHsl(avg(ranked[0]))
  // Второй цвет — заметно другой оттенок, иначе соседний сдвинутый.
  const second = ranked.find((b) => Math.min(Math.abs(b.i - ranked[0].i), 24 - Math.abs(b.i - ranked[0].i)) >= 3)
  const [h2, s2] = second ? rgbToHsl(avg(second)) : [(h1 + 40) % 360, s1]

  // Акцент должен читаться на тёмном фоне: светлота 0.62–0.7, насыщенность не ниже 0.55.
  const accent = hslToHex(h1, Math.max(0.55, Math.min(0.95, s1 + 0.15)), 0.66)
  const aura1 = hslToHex(h1, Math.min(0.9, s1 + 0.1), 0.5)
  const aura2 = hslToHex(h2, Math.min(0.85, s2 + 0.1), 0.42)
  const accentInk = luminance(accent) > 0.4 ? '#121214' : '#ffffff'
  return { accent, accentInk, aura1, aura2 }
}

export function paletteFor(src: string | null | undefined): Promise<Palette> {
  if (!src) return Promise.resolve(DEFAULT_PALETTE)
  let p = cache.get(src)
  if (!p) {
    p = extract(src).catch(() => DEFAULT_PALETTE)
    cache.set(src, p)
    if (cache.size > 300) cache.delete(cache.keys().next().value as string)
  }
  return p
}

export function applyPalette(p: Palette, el: HTMLElement = document.documentElement) {
  el.style.setProperty('--accent', p.accent)
  el.style.setProperty('--accent-ink', p.accentInk)
  el.style.setProperty('--aura-1', p.aura1)
  el.style.setProperty('--aura-2', p.aura2)
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', mix(p.aura1, '#0e0f12', 0.82))
}

function mix(a: string, b: string, t: number): string {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16))
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16))
  return `#${pa
    .map((v, i) =>
      Math.round(v * (1 - t) + pb[i] * t)
        .toString(16)
        .padStart(2, '0'),
    )
    .join('')}`
}

export const inkFor = (hex: string) => (luminance(hex) > 0.4 ? '#121214' : '#ffffff')
