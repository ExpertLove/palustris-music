import { useEffect, useMemo, useRef, useState } from 'react'
import { EQ_BANDS, EQ_LABELS, EQ_MAX, EQ_PRESETS } from '../lib/data'
import { engine, isIOS } from '../player/engine'
import { usePlayer } from '../player/store'

const W = 600
const H = 240
const PAD_X = 34
const PAD_Y = 26
const fx = (i: number) => PAD_X + (i / (EQ_BANDS.length - 1)) * (W - PAD_X * 2)
const fy = (db: number) => PAD_Y + ((EQ_MAX - db) / (EQ_MAX * 2)) * (H - PAD_Y * 2)
const toDb = (y: number) => Math.max(-EQ_MAX, Math.min(EQ_MAX, EQ_MAX - ((y - PAD_Y) / (H - PAD_Y * 2)) * EQ_MAX * 2))

/** Логарифмическая ось частот 20 Гц – 20 кГц ↔ x. */
const LOG_MIN = Math.log10(EQ_BANDS[0])
const LOG_MAX = Math.log10(EQ_BANDS[EQ_BANDS.length - 1])
const xOfFreq = (f: number) => PAD_X + ((Math.log10(f) - LOG_MIN) / (LOG_MAX - LOG_MIN)) * (W - PAD_X * 2)

function smoothPath(pts: [number, number][]): string {
  if (!pts.length) return ''
  let d = `M${pts[0][0]},${pts[0][1]}`
  for (let i = 1; i < pts.length; i++) {
    const [x0, y0] = pts[i - 1]
    const [x1, y1] = pts[i]
    const cx = (x0 + x1) / 2
    d += ` C${cx},${y0} ${cx},${y1} ${x1},${y1}`
  }
  return d
}

export function Equalizer() {
  const eq = usePlayer((s) => s.eq)
  const setEq = usePlayer((s) => s.setEq)
  const crossfade = usePlayer((s) => s.crossfade)
  const setCrossfade = usePlayer((s) => s.setCrossfade)
  const normalize = usePlayer((s) => s.normalize)
  const setNormalize = usePlayer((s) => s.setNormalize)
  const rate = usePlayer((s) => s.rate)
  const setRate = usePlayer((s) => s.setRate)
  const svg = useRef<SVGSVGElement>(null)
  const [active, setActive] = useState<number | null>(null)
  const [curve, setCurve] = useState<string>('')

  // Реальная АЧХ фильтров (если Web Audio уже работает), иначе — сглаженная линия по точкам.
  const freqs = useMemo(() => {
    const n = 120
    const a = new Float32Array(n)
    for (let i = 0; i < n; i++) a[i] = Math.pow(10, LOG_MIN + ((LOG_MAX - LOG_MIN) * i) / (n - 1))
    return a
  }, [])
  useEffect(() => {
    const id = window.setTimeout(() => {
      const r = eq.on ? engine.response(freqs) : null
      if (r) {
        const pts: [number, number][] = Array.from(freqs, (f, i) => [xOfFreq(f), fy(Math.max(-EQ_MAX, Math.min(EQ_MAX, r[i])))])
        setCurve(pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' '))
      } else {
        setCurve(smoothPath(eq.gains.map((g, i) => [fx(i), fy(eq.on ? g : 0)])))
      }
    }, 60)
    return () => window.clearTimeout(id)
  }, [eq, freqs])

  const setBand = (i: number, db: number) => {
    const gains = eq.gains.slice()
    gains[i] = Math.round(db * 2) / 2
    setEq({ gains, on: true, preset: 'custom', custom: gains })
  }

  const pointer = (e: React.PointerEvent, i?: number) => {
    const s = svg.current
    if (!s) return
    const r = s.getBoundingClientRect()
    const x = ((e.clientX - r.left) / r.width) * W
    const y = ((e.clientY - r.top) / r.height) * H
    let band = i ?? active
    if (band === null || band === undefined) {
      // Клик мимо ручки — ближайшая полоса.
      band = EQ_BANDS.reduce((best, _, k) => (Math.abs(fx(k) - x) < Math.abs(fx(best) - x) ? k : best), 0)
    }
    setActive(band)
    setBand(band, toDb(y))
  }

  const presetGains = (id: string) => (id === 'custom' ? eq.custom ?? eq.gains : EQ_PRESETS.find((p) => p.id === id)?.gains)

  return (
    <div className="eq">
      <div className="eq-top">
        <div>
          <b>{eq.on ? 'Включён' : 'Выключен'}</b>
          <div className="small muted">{isIOS ? 'На iPhone звук через эквалайзер может прерываться при заблокированном экране.' : 'Настройки сохраняются на этом устройстве.'}</div>
        </div>
        <button type="button" className="switch" role="switch" aria-checked={eq.on} aria-label="Эквалайзер" onClick={() => setEq({ on: !eq.on })} />
      </div>

      <div className="eq-presets" role="listbox" aria-label="Пресеты">
        {EQ_PRESETS.map((p) => (
          <button
            key={p.id}
            type="button"
            role="option"
            aria-selected={eq.on && eq.preset === p.id}
            className={`chip ${eq.on && eq.preset === p.id ? 'on' : ''}`}
            onClick={() => setEq({ on: true, preset: p.id, gains: p.gains.slice() })}
          >
            {p.title}
          </button>
        ))}
        {eq.custom && (
          <button type="button" role="option" aria-selected={eq.preset === 'custom'} className={`chip ${eq.on && eq.preset === 'custom' ? 'on' : ''}`} onClick={() => setEq({ on: true, preset: 'custom', gains: presetGains('custom')!.slice() })}>
            Мой
          </button>
        )}
      </div>

      <div
        className={`eq-graph ${eq.on ? '' : 'off'}`}
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId)
          pointer(e)
        }}
        onPointerMove={(e) => active !== null && e.buttons && pointer(e)}
        onPointerUp={() => setActive(null)}
      >
        <svg ref={svg} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label="Кривая эквалайзера">
          <defs>
            <linearGradient id="eqfill" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stopColor="var(--accent)" stopOpacity=".32" />
              <stop offset="1" stopColor="var(--accent)" stopOpacity="0" />
            </linearGradient>
          </defs>
          <g className="grid">
            {EQ_BANDS.map((_, i) => (
              <line key={i} x1={fx(i)} x2={fx(i)} y1={PAD_Y} y2={H - PAD_Y} />
            ))}
            {[6, -6].map((d) => (
              <line key={d} x1={PAD_X} x2={W - PAD_X} y1={fy(d)} y2={fy(d)} />
            ))}
          </g>
          <line className="zero" x1={PAD_X} x2={W - PAD_X} y1={fy(0)} y2={fy(0)} />
          <text className="db" x={4} y={fy(EQ_MAX) + 4}>+{EQ_MAX}</text>
          <text className="db" x={10} y={fy(0) + 4}>0</text>
          <text className="db" x={4} y={fy(-EQ_MAX) + 4}>−{EQ_MAX}</text>
          {curve && <path className="area" d={`${curve} L${W - PAD_X},${fy(-EQ_MAX)} L${PAD_X},${fy(-EQ_MAX)} Z`} />}
          {curve && <path className="curve" d={curve} />}
          {eq.gains.map((g, i) => (
            <circle
              key={i}
              className={`handle ${active === i ? 'active' : ''}`}
              cx={fx(i)}
              cy={fy(eq.on ? g : 0)}
              r={active === i ? 9 : 7}
              tabIndex={0}
              role="slider"
              aria-label={`${EQ_LABELS[i]} Гц`}
              aria-valuemin={-EQ_MAX}
              aria-valuemax={EQ_MAX}
              aria-valuenow={g}
              onPointerDown={(e) => {
                e.stopPropagation()
                ;(e.currentTarget.ownerSVGElement?.parentElement as HTMLElement)?.setPointerCapture(e.pointerId)
                setActive(i)
              }}
              onKeyDown={(e) => {
                if (e.key === 'ArrowUp') setBand(i, Math.min(EQ_MAX, g + 1))
                else if (e.key === 'ArrowDown') setBand(i, Math.max(-EQ_MAX, g - 1))
                else return
                e.preventDefault()
              }}
            />
          ))}
          {EQ_LABELS.map((l, i) => (
            <text key={l} className="lbl" x={fx(i)} y={H - 6}>
              {l}
            </text>
          ))}
          {active !== null && (
            <text className="lbl" x={fx(active)} y={Math.max(14, fy(eq.gains[active]) - 14)} style={{ fill: 'var(--ink)' }}>
              {eq.gains[active] > 0 ? '+' : ''}
              {eq.gains[active]} дБ
            </text>
          )}
        </svg>
      </div>

      <div className="eq-row">
        <span className="lbl">Предусилитель</span>
        <input
          className="range"
          type="range"
          min={-12}
          max={6}
          step={0.5}
          value={eq.preamp}
          style={{ ['--p' as string]: `${((eq.preamp + 12) / 18) * 100}%` }}
          onChange={(e) => setEq({ preamp: Number(e.target.value), on: true })}
          aria-label="Предусилитель"
        />
        <span className="val">
          {eq.preamp > 0 ? '+' : ''}
          {eq.preamp} дБ
        </span>
      </div>

      <div>
        <div className="setting">
          <div className="txt">
            <b>Ровная громкость</b>
            <span>Тихие треки станут громче, громкие — тише.</span>
          </div>
          <button type="button" className="switch" role="switch" aria-checked={normalize} aria-label="Ровная громкость" onClick={() => setNormalize(!normalize)} />
        </div>
        <div className="setting">
          <div className="txt">
            <b>Плавный переход</b>
            <span>{crossfade ? `Треки перетекают друг в друга за ${crossfade} с.` : 'Выключен: следующий трек начинается сразу.'}</span>
          </div>
          <input
            className="range"
            type="range"
            min={0}
            max={12}
            step={1}
            value={crossfade}
            style={{ width: 140, ['--p' as string]: `${(crossfade / 12) * 100}%` }}
            onChange={(e) => setCrossfade(Number(e.target.value))}
            aria-label="Длительность перехода"
          />
        </div>
        <div className="setting">
          <div className="txt">
            <b>Скорость</b>
            <span>Тон голоса не меняется.</span>
          </div>
          <div className="seg">
            {[0.75, 1, 1.25, 1.5].map((r) => (
              <button key={r} aria-selected={rate === r} onClick={() => setRate(r)}>
                {String(r).replace('.', ',')}×
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
