// Аудиодвижок: две «деки» (для кроссфейда и бесшовного перехода) и цепочка Web Audio:
// дека → гейн деки (переход) → уровень трека (ровная громкость) → предусилитель → 10 полос эквалайзера →
// мастер → анализатор → выход.
import { streamUrl } from '../lib/api'
import type { Track } from '../lib/types'
import { EQ_BANDS } from '../lib/data'

export interface EngineEvents {
  time: (t: number, d: number) => void
  state: (playing: boolean) => void
  buffering: (b: boolean) => void
  ended: () => void
  error: (track: Track | null) => void
  started: (track: Track) => void
}

export const isIOS =
  typeof navigator !== 'undefined' &&
  (/iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))

interface Deck {
  el: HTMLAudioElement
  track: Track | null
  src: MediaElementAudioSourceNode | null
  /** Кроссфейд: 0…1. */
  gain: GainNode | null
  /** Ровная громкость: множитель этого трека. Отдельная линия, чтобы переход и выравнивание не мешали друг другу. */
  level: GainNode | null
}

const dB = (v: number) => Math.pow(10, v / 20)

export class Engine {
  decks: Deck[]
  active = 0
  ctx: AudioContext | null = null
  analyser: AnalyserNode | null = null
  private preamp: GainNode | null = null
  private filters: BiquadFilterNode[] = []
  private master: GainNode | null = null
  private volume = 0.9
  private muted = false
  private rate = 1
  private eq = { on: false, gains: new Array(10).fill(0) as number[], preamp: 0 }
  private normalize = false
  private levelOf: (t: Track) => number = () => 1
  private fading = false
  private on: Partial<EngineEvents> = {}

  constructor() {
    this.decks = [0, 1].map((i) => this.makeDeck(i))
  }

  setHandlers(h: Partial<EngineEvents>) {
    this.on = h
  }

  get el() {
    return this.decks[this.active].el
  }
  get track() {
    return this.decks[this.active].track
  }
  get graphOn() {
    return !!this.ctx
  }

  private makeDeck(i: number): Deck {
    const el = new Audio()
    el.preload = 'auto'
    el.crossOrigin = 'use-credentials'
    const d: Deck = { el, track: null, src: null, gain: null, level: null }
    const mine = () => this.decks?.[this.active] === d
    el.addEventListener('timeupdate', () => mine() && this.on.time?.(el.currentTime, el.duration || d.track?.duration || 0))
    el.addEventListener('durationchange', () => mine() && this.on.time?.(el.currentTime, el.duration || 0))
    el.addEventListener('play', () => mine() && this.on.state?.(true))
    el.addEventListener('pause', () => mine() && !this.fading && this.on.state?.(false))
    el.addEventListener('waiting', () => mine() && this.on.buffering?.(true))
    el.addEventListener('playing', () => {
      if (!mine()) return
      this.on.buffering?.(false)
      if (d.track) this.on.started?.(d.track)
    })
    el.addEventListener('canplay', () => mine() && this.on.buffering?.(false))
    el.addEventListener('ended', () => mine() && this.on.ended?.())
    el.addEventListener('error', () => {
      if (!mine() || !el.src) return
      this.on.buffering?.(false)
      this.on.error?.(d.track)
    })
    void i
    return d
  }

  /** Web Audio включаем по жесту пользователя. На iOS — только если нужен эквалайзер:
   *  там цепочка Web Audio обрывает звук при блокировке экрана. */
  ensureGraph(force = false): boolean {
    if (this.ctx) {
      if (this.ctx.state === 'suspended') void this.ctx.resume()
      return true
    }
    if (isIOS && !force) return false
    try {
      const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
      const ctx = new Ctx({ latencyHint: 'playback' })
      this.ctx = ctx
      this.preamp = ctx.createGain()
      this.filters = EQ_BANDS.map((f, i) => {
        const b = ctx.createBiquadFilter()
        b.type = i === 0 ? 'lowshelf' : i === EQ_BANDS.length - 1 ? 'highshelf' : 'peaking'
        b.frequency.value = f
        b.Q.value = 1.1
        b.gain.value = 0
        return b
      })
      this.master = ctx.createGain()
      this.analyser = ctx.createAnalyser()
      this.analyser.fftSize = 512
      this.analyser.smoothingTimeConstant = 0.8

      for (const d of this.decks) {
        d.src = ctx.createMediaElementSource(d.el)
        d.gain = ctx.createGain()
        d.gain.gain.value = d === this.decks[this.active] ? 1 : 0
        d.level = ctx.createGain()
        d.level.gain.value = this.levelFor(d)
        d.src.connect(d.gain).connect(d.level).connect(this.preamp)
        d.el.volume = 1
      }
      let node: AudioNode = this.preamp
      for (const f of this.filters) node = node.connect(f)
      node.connect(this.master)
      this.master.connect(this.analyser).connect(ctx.destination)
      this.applyEq()
      this.applyVolume()
      if (ctx.state === 'suspended') void ctx.resume()
      return true
    } catch (e) {
      console.warn('web audio unavailable', e)
      this.ctx = null
      return false
    }
  }

  private levelFor(d: Deck): number {
    return this.normalize && d.track ? this.levelOf(d.track) : 1
  }

  /** Уровень деки: сразу (трек только что загружен) или плавно (громкость пришла посреди трека). */
  private applyLevel(d: Deck, smooth: boolean) {
    if (!this.ctx || !d.level) return
    const p = d.level.gain
    const v = this.levelFor(d)
    const t = this.ctx.currentTime
    p.cancelScheduledValues(0)
    if (smooth) {
      p.setValueAtTime(p.value, t)
      p.setTargetAtTime(v, t, 0.25)
    } else {
      p.setValueAtTime(v, t)
    }
  }

  /** Откуда брать множитель трека (см. loudness.ts). */
  setLevelProvider(fn: (t: Track) => number) {
    this.levelOf = fn
  }

  /** Пришла громкость каких-то треков: плавно подстраиваем обе деки. */
  refreshLevels() {
    for (const d of this.decks) this.applyLevel(d, true)
  }

  /** Сразу, без автоматизации: обрывает и запланированные кривые перехода. */
  private setNow(p: AudioParam, v: number) {
    if (!this.ctx) return
    p.cancelScheduledValues(0)
    p.setValueAtTime(v, this.ctx.currentTime)
  }

  private applyEq() {
    if (!this.ctx) return
    const t = this.ctx.currentTime
    this.filters.forEach((f, i) => f.gain.setTargetAtTime(this.eq.on ? this.eq.gains[i] : 0, t, 0.04))
    // Подъём полос режем предусилителем, чтобы не было перегруза.
    const peak = this.eq.on ? Math.max(0, ...this.eq.gains) : 0
    const pre = this.eq.on ? this.eq.preamp - peak * 0.6 : 0
    this.preamp?.gain.setTargetAtTime(dB(pre), t, 0.04)
  }

  private applyVolume() {
    const v = this.muted ? 0 : Math.pow(this.volume, 2)
    if (this.ctx && this.master) {
      this.master.gain.setTargetAtTime(v, this.ctx.currentTime, 0.03)
      for (const d of this.decks) d.el.volume = 1
    } else {
      for (const d of this.decks) d.el.volume = v
    }
  }

  setVolume(v: number) {
    this.volume = Math.max(0, Math.min(1, v))
    this.applyVolume()
  }
  setMuted(m: boolean) {
    this.muted = m
    this.applyVolume()
  }
  setRate(r: number) {
    this.rate = r
    for (const d of this.decks) {
      d.el.playbackRate = r
      d.el.preservesPitch = true
    }
  }
  setEq(eq: { on: boolean; gains: number[]; preamp: number }) {
    this.eq = { on: eq.on, gains: eq.gains.slice(), preamp: eq.preamp }
    if (eq.on) this.ensureGraph(true)
    this.applyEq()
  }
  /** `force` — включить Web Audio сразу (по жесту пользователя). При восстановлении не форсируем:
   *  цепочка всё равно создаётся при первом запуске музыки, а на iPhone без нужды её лучше не включать. */
  setNormalize(n: boolean, force = true) {
    this.normalize = n
    if (n && force) this.ensureGraph(true)
    this.refreshLevels()
  }

  /** АЧХ текущей цепочки для кривой на экране эквалайзера. */
  response(freqs: Float32Array<ArrayBuffer>): Float32Array | null {
    if (!this.filters.length) return null
    const total = new Float32Array(freqs.length)
    const mag = new Float32Array(freqs.length)
    const phase = new Float32Array(freqs.length)
    for (const f of this.filters) {
      f.getFrequencyResponse(freqs, mag, phase)
      for (let i = 0; i < freqs.length; i++) total[i] += 20 * Math.log10(mag[i] || 1e-6)
    }
    return total
  }

  private setSrc(d: Deck, t: Track) {
    d.track = t
    d.el.src = streamUrl(t)
    d.el.playbackRate = this.rate
    d.el.preservesPitch = true
    this.applyLevel(d, false)
  }

  /** Загрузить трек в активную деку (или взять уже подгруженный во второй). */
  load(t: Track, opts: { autoplay: boolean; startAt?: number }) {
    this.fading = false
    const other = this.decks[1 - this.active]
    const cur = this.decks[this.active]
    if (other.track?.id === t.id && other.el.src) {
      cur.el.pause()
      this.active = 1 - this.active
    } else if (cur.track?.id !== t.id || !cur.el.src) {
      cur.el.pause()
      this.setSrc(cur, t)
    }
    const d = this.decks[this.active]
    for (const x of this.decks) {
      if (x !== d) x.el.pause()
      if (x.gain) this.setNow(x.gain.gain, x === d ? 1 : 0)
    }
    if (opts.startAt) {
      const s = opts.startAt
      const seek = () => {
        try {
          d.el.currentTime = s
        } catch {
          /* ещё нет метаданных */
        }
      }
      if (d.el.readyState >= 1) seek()
      else d.el.addEventListener('loadedmetadata', seek, { once: true })
    } else if (d.el.currentTime > 0) {
      d.el.currentTime = 0
    }
    this.on.time?.(opts.startAt || 0, d.el.duration || t.duration || 0)
    if (opts.autoplay) {
      this.on.buffering?.(true)
      d.el.play().catch((e) => {
        if (e?.name === 'NotAllowedError') this.on.state?.(false)
      })
    }
  }

  /** Подгрузить следующий трек во вторую деку. */
  preload(t: Track | null) {
    const d = this.decks[1 - this.active]
    if (!t || d.track?.id === t.id) return
    d.el.pause()
    this.setSrc(d, t)
    d.el.load()
  }

  /** Плавный переход: новый трек нарастает по синусу, старый затихает по косинусу. Сумма мощностей
   *  sin² + cos² = 1, поэтому посередине громкость не проседает (как в Android-приложении). */
  crossfadeTo(t: Track, secs: number): boolean {
    if (!this.ctx || secs <= 0) return false
    const from = this.decks[this.active]
    const to = this.decks[1 - this.active]
    if (to.track?.id !== t.id || !to.el.src) this.setSrc(to, t)
    to.el.currentTime = 0
    const now = this.ctx.currentTime
    const start = from.gain!.gain.value || 1
    const n = 64
    const fadeIn = new Float32Array(n)
    const fadeOut = new Float32Array(n)
    for (let i = 0; i < n; i++) {
      const a = ((i / (n - 1)) * Math.PI) / 2
      fadeIn[i] = Math.sin(a)
      fadeOut[i] = Math.cos(a) * start
    }
    try {
      to.gain!.gain.cancelScheduledValues(0)
      to.gain!.gain.setValueCurveAtTime(fadeIn, now, secs)
      from.gain!.gain.cancelScheduledValues(0)
      from.gain!.gain.setValueCurveAtTime(fadeOut, now, secs)
    } catch {
      // Старые браузеры: линейно.
      this.setNow(to.gain!.gain, 0)
      to.gain!.gain.linearRampToValueAtTime(1, now + secs)
      this.setNow(from.gain!.gain, start)
      from.gain!.gain.linearRampToValueAtTime(0, now + secs)
    }
    this.fading = true
    this.active = 1 - this.active
    to.el.play().catch(() => this.on.error?.(t))
    window.setTimeout(() => {
      from.el.pause()
      this.fading = false
    }, secs * 1000 + 150)
    return true
  }

  play() {
    this.ensureGraph()
    const el = this.el
    if (!el.src) return
    el.play().catch((e) => {
      if (e?.name === 'NotAllowedError') this.on.state?.(false)
    })
  }
  pause() {
    this.el.pause()
  }
  seek(t: number) {
    const el = this.el
    if (!Number.isFinite(t)) return
    try {
      el.currentTime = Math.max(0, Math.min(t, (el.duration || t) - 0.25))
    } catch {
      /* noop */
    }
  }
  get time() {
    return this.el.currentTime
  }
  get duration() {
    return this.el.duration || this.track?.duration || 0
  }
  get paused() {
    return this.el.paused
  }
  /** Сколько секунд от начала уже загружено. */
  buffered(): number {
    const el = this.el
    const b = el.buffered
    let end = 0
    for (let i = 0; i < b.length; i++) if (b.start(i) <= el.currentTime + 1) end = Math.max(end, b.end(i))
    return end
  }
  stop() {
    for (const d of this.decks) {
      d.el.pause()
      d.el.removeAttribute('src')
      d.el.load()
      d.track = null
    }
  }
}

export const engine = new Engine()
