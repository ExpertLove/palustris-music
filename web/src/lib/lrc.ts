export interface LrcLine {
  t: number
  text: string
}

/** «[01:02.50] текст» → строки по времени. Пустые строки оставляем как паузы. */
export function parseLrc(src: string): LrcLine[] {
  const out: LrcLine[] = []
  for (const raw of src.split(/\r?\n/)) {
    const tags = [...raw.matchAll(/\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g)]
    if (!tags.length) continue
    const text = raw.replace(/\[[^\]]*\]/g, '').trim()
    for (const m of tags) {
      const frac = m[3] ? Number(m[3].padEnd(3, '0')) / 1000 : 0
      out.push({ t: Number(m[1]) * 60 + Number(m[2]) + frac, text })
    }
  }
  return out.sort((a, b) => a.t - b.t)
}

export function activeLine(lines: LrcLine[], time: number): number {
  let lo = 0
  let hi = lines.length - 1
  let ans = -1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (lines[mid].t <= time + 0.15) {
      ans = mid
      lo = mid + 1
    } else hi = mid - 1
  }
  return ans
}
