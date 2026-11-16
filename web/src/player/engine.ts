// Аудиодвижок: две «деки» (для кроссфейда и бесшовного перехода) и цепочка Web Audio:
// дека → гейн деки → предусилитель → 10 полос эквалайзера → [компрессор] → мастер → анализатор → выход.
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
  gain: GainNode | null
}

const dB = (v: number) => Math.pow(10, v / 20)

export class Engine {
  decks: Deck[]
  active = 0
  ctx: AudioContext | null = null
  analyser: AnalyserNode | null = null
  private preamp: GainNode | null = null
  private filters: BiquadFilterNode[] = []
  private comp: DynamicsCompressorNode | null = null
  private makeup: GainNode | null = null
  private master: GainNode | null = null
  private volume = 0.9
  private muted = false
  private rate = 1
  private eq = { on: false, gains: new Array(10).fill(0) as number[], preamp: 0 }
  private normalize = false
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
    const d: Deck = { el, track: null, src: null, gain: null }
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
      this.comp = ctx.createDynamicsCompressor()
      this.comp.threshold.value = -24
      this.comp.knee.value = 24
      this.comp.ratio.value = 4
      this.comp.attack.value = 0.01
      this.comp.release.value = 0.25
      this.makeup = ctx.createGain()
      this.makeup.gain.value = dB(5)
      this.master = ctx.createGain()
      this.analyser = ctx.createAnalyser()
      this.analyser.fftSize = 512
      this.analyser.smoothingTimeConstant = 0.8

      for (const d of this.decks) {
        d.src = ctx.createMediaElementSource(d.el)
        d.gain = ctx.createGain()
        d.gain.gain.value = d === this.decks[this.active] ? 1 : 0
        d.src.connect(d.gain).connect(this.preamp)
        d.el.volume = 1
      }
      let node: AudioNode = this.preamp
      for (const f of this.filters) node = node.connect(f)
      this.routeTail()
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

  private routeTail() {
    const last = this.filters[this.filters.length - 1]
    if (!last || !this.master || !this.comp || !this.makeup) return
    last.disconnect()
    this.comp.disconnect()
    this.makeup.disconnect()
    if (this.normalize) last.connect(this.comp).connect(this.makeup).connect(this.master)
    else last.connect(this.master)
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
  setNormalize(n: boolean) {
    this.normalize = n
    if (n) this.ensureGraph(true)
    this.routeTail()
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
      if (x.gain && this.ctx) x.gain.gain.setValueAtTime(x === d ? 1 : 0, this.ctx.currentTime)
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

  /** Плавный переход: новый трек нарастает, старый затихает. */
  crossfadeTo(t: Track, secs: number): boolean {
    if (!this.ctx || secs <= 0) return false
    const from = this.decks[this.active]
    const to = this.decks[1 - this.active]
    if (to.track?.id !== t.id || !to.el.src) this.setSrc(to, t)
    to.el.currentTime = 0
    const now = this.ctx.currentTime
    to.gain!.gain.cancelScheduledValues(now)
    to.gain!.gain.setValueAtTime(0.0001, now)
    to.gain!.gain.exponentialRampToValueAtTime(1, now + secs)
    from.gain!.gain.cancelScheduledValues(now)
    from.gain!.gain.setValueAtTime(from.gain!.gain.value || 1, now)
    from.gain!.gain.linearRampToValueAtTime(0, now + secs)
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
