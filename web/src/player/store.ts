import { create } from 'zustand'
import { api } from '../lib/api'
import { EQ_PRESETS, getRegion, loadWaveSettings, saveWaveSettings } from '../lib/data'
import { bigThumb, shuffled } from '../lib/format'
import type { Substitute, Track, WaveSettings } from '../lib/types'
import { toast } from '../store/ui'
import { engine, isIOS } from './engine'
import { levelOf, requestLoudness } from './loudness'

export type Repeat = 'off' | 'all' | 'one'

/** Откуда очередь: волна и радио бесконечные, микс — подборка сервера, список — всё остальное. */
export interface Source {
  kind: 'wave' | 'list' | 'radio' | 'mix'
  title: string
  href?: string
}

/** Рекомендации: тут уместны «Не нравится» рядом с лайком и объяснение, почему трек здесь. */
export const isRecommended = (s: Source | null | undefined) => !!s && (s.kind === 'wave' || s.kind === 'radio' || s.kind === 'mix')

/** Скрытое пользователем («Не нравится», скрытые исполнители): проверку подключает `store/taste`,
 *  чтобы модули не ссылались друг на друга. */
let isHidden: (t: Track) => boolean = () => false
export const setHiddenCheck = (fn: (t: Track) => boolean) => {
  isHidden = fn
}

/** Объяснение «почему в подборке» не тащим за треком в другие очереди. */
const plain = (t: Track): Track => {
  const { why: _why, ...rest } = t
  void _why
  return rest
}

export interface EqState {
  on: boolean
  preset: string
  gains: number[]
  preamp: number
  custom: number[] | null
}

interface PlayerState {
  queue: Track[]
  index: number
  playing: boolean
  buffering: boolean
  time: number
  duration: number
  volume: number
  muted: boolean
  shuffle: boolean
  repeat: Repeat
  rate: number
  source: Source | null
  wave: boolean
  crossfade: number
  normalize: boolean
  eq: EqState
  sleepAt: number | null
  sleepEndOfTrack: boolean
  /** Сколько треков пользователь поставил «в очередь» сразу после текущего. */
  queued: number
  unshuffled: Track[] | null
  /** Настройки «Моей волны»: характер, настроение, занятие. */
  waveSettings: WaveSettings
  /** YouTube не отдал трек `id`, играет такая же запись из другого каталога. */
  substitute: (Substitute & { id: string }) | null

  playList: (list: Track[], start?: number, source?: Source) => void
  playTrack: (t: Track, list?: Track[], source?: Source) => void
  startWave: () => Promise<void>
  /** Новые настройки волны. Если она играет, всё впереди сразу пересобирается. */
  setWaveSettings: (w: WaveSettings) => Promise<void>
  /** Бесконечное радио: похожее на этот трек. */
  startRadio: (t: Track) => Promise<void>
  /** Радио исполнителя: он сам и похожие. */
  startArtistRadio: (p: { id?: string | null; name: string; href?: string }) => Promise<void>
  /** Заменить всё впереди одним разом: текущий трек доигрывает. */
  replaceUpcoming: (list: Track[], source?: Source) => void
  /** Убрать из очереди впереди всё, что подходит под условие. */
  removeUpcoming: (pred: (t: Track) => boolean) => void
  /** Следующий трек без «пропуска» и без повтора трека: после «Не нравится». */
  skipCurrent: () => void
  toggle: () => void
  play: () => void
  pause: () => void
  next: (user?: boolean) => void
  prev: () => void
  jumpTo: (i: number) => void
  seek: (t: number) => void
  setVolume: (v: number) => void
  toggleMute: () => void
  toggleShuffle: () => void
  cycleRepeat: () => void
  playNext: (t: Track) => void
  addToQueue: (t: Track | Track[]) => void
  removeAt: (i: number) => void
  move: (from: number, to: number) => void
  clearUpcoming: () => void
  setEq: (p: Partial<EqState>) => void
  setCrossfade: (s: number) => void
  setNormalize: (b: boolean) => void
  setRate: (r: number) => void
  setSleep: (min: number | 'track' | null) => void
}

const SAVE_KEY = 'music.player.v2'

const defaultEq: EqState = { on: false, preset: 'flat', gains: EQ_PRESETS[0].gains.slice(), preamp: 0, custom: null }

// Состояние текущего проигрывания, не нужное интерфейсу.
let counted = false
let startedAt = 0
let preloadedFor = ''
let fadedFrom = ''
let refilling = false
let lastExtend = 0
let errorsInRow = 0
let prefetchedFor = ''
/** Когда последний раз спрашивали про замену: `playing` приходит и после паузы, и после перемотки. */
let substituteAsked = { id: '', at: 0 }

/** У `<audio>` нет доступа к заголовкам ответа, поэтому о подмене спрашиваем сервер отдельно. */
function askSubstitute(t: Track) {
  const now = Date.now()
  if (substituteAsked.id === t.id && now - substituteAsked.at < 60_000) return
  substituteAsked = { id: t.id, at: now }
  api
    .substitute(t.id)
    .then((sub) => {
      const cur = usePlayer.getState()
      if (cur.queue[cur.index]?.id !== t.id) return
      usePlayer.setState({ substitute: sub ? { ...sub, id: t.id } : null })
    })
    .catch(() => null)
}
/** Пропуски подряд в волне: после двух она перестраивается, как в приложении. */
let skipsInRow = 0

// ───────────── прослушивания ─────────────
// В историю трек уходит, когда перестал играть, вместе с тем, сколько секунд он на самом деле звучал:
// по ним сервер считает минуты в статистике и долю дослушанного в рекомендациях. Перемотка не считается.
interface Listen {
  track: Track
  at: number
  secs: number
  pos: number
  dur: number
}
let listen: Listen | null = null

const enough = (l: Listen) => l.secs >= Math.min(30, (l.dur || 60) / 2)

/** Выход из аккаунта: недослушанное не должно уйти в историю следующего. */
export const dropListen = () => {
  listen = null
}

/** Догрузка волны или радио; назначается внутри хранилища. */
let extendQueue: () => Promise<void> = async () => {}

function finishListen(keepalive = false) {
  const l = listen
  listen = null
  if (!l || !enough(l)) return
  api.addHistory({ ...l.track, duration: Math.round(l.dur || l.track.duration) }, l.at, Math.round(l.secs), keepalive).catch(() => null)
}

export const current = (s: Pick<PlayerState, 'queue' | 'index'>) => s.queue[s.index] ?? null

function dedupe(list: Track[]): Track[] {
  const seen = new Set<string>()
  return list.filter((t) => (seen.has(t.id) ? false : (seen.add(t.id), true)))
}

export const usePlayer = create<PlayerState>((set, get) => {
  const load = (autoplay: boolean, startAt?: number) => {
    const t = current(get())
    if (!t) return
    counted = false
    startedAt = Date.now()
    preloadedFor = ''
    if (autoplay) engine.ensureGraph()
    engine.load(t, { autoplay, startAt })
    set({ time: startAt || 0, duration: t.duration || 0, buffering: autoplay })
    updateMediaSession(t)
  }

  const nextIndex = (): number | null => {
    const s = get()
    if (s.index + 1 < s.queue.length) return s.index + 1
    if (s.repeat === 'all' && s.queue.length) return 0
    return null
  }

  /** Волна и радио бесконечные: догружаем продолжение. Радио — похожее на то, что играет сейчас,
   *  так оно плавно дрейфует. */
  const extend = async () => {
    const s = get()
    const cur = current(s)
    const radio = s.source?.kind === 'radio'
    if (refilling || !cur || (!s.wave && !radio)) return
    refilling = true
    lastExtend = Date.now()
    try {
      const fresh = s.wave ? await api.wave(s.waveSettings, getRegion()) : await api.radio(cur)
      const have = new Set(get().queue.map((t) => t.id))
      const add = fresh.filter((t) => !have.has(t.id) && !isHidden(t))
      if (add.length) set({ queue: [...get().queue, ...add] })
    } catch {
      /* попробуем позже */
    } finally {
      refilling = false
    }
  }

  extendQueue = extend

  /** Два пропуска подряд — волна не попадает: сервер уже знает о пропусках, берём свежую. */
  const rebuildWaveAhead = async () => {
    const s = get()
    const cur = current(s)
    if (!s.wave || !cur) return
    const fresh = await api.wave(s.waveSettings, getRegion()).catch(() => [] as Track[])
    if (fresh.length < 5 || !get().wave || current(get())?.id !== cur.id) return
    get().replaceUpcoming(fresh)
  }

  const go = (i: number, autoplay = true) => {
    set({ index: i, queued: Math.max(0, get().queued - 1) })
    load(autoplay)
  }

  return {
    queue: [],
    index: -1,
    playing: false,
    buffering: false,
    time: 0,
    duration: 0,
    volume: 0.85,
    muted: false,
    shuffle: false,
    repeat: 'off',
    rate: 1,
    source: null,
    wave: false,
    crossfade: 0,
    // Ровная громкость по умолчанию включена, как в приложении. На iPhone — нет: для неё нужен Web Audio,
    // а с ним Safari может прерывать звук на заблокированном экране.
    normalize: !isIOS,
    eq: defaultEq,
    sleepAt: null,
    sleepEndOfTrack: false,
    queued: 0,
    unshuffled: null,
    substitute: null,
    waveSettings: loadWaveSettings(),

    playList: (list, start = 0, source) => {
      list = dedupe(list)
      if (!list.length) return
      const first = list[Math.max(0, Math.min(start, list.length - 1))]
      let queue = list
      let index = list.indexOf(first)
      if (get().shuffle) {
        queue = [first, ...shuffled(list.filter((t) => t !== first))]
        index = 0
      }
      errorsInRow = 0
      skipsInRow = 0
      set({
        queue,
        index,
        unshuffled: get().shuffle ? list : null,
        source: source ?? { kind: 'list', title: '' },
        wave: source?.kind === 'wave',
        queued: 0,
      })
      load(true)
    },

    playTrack: (t, list, source) => {
      const cur = current(get())
      if (cur?.id === t.id) return get().toggle()
      const l = list && list.some((x) => x.id === t.id) ? list : [t]
      get().playList(l, l.findIndex((x) => x.id === t.id), source)
    },

    startWave: async () => {
      if (get().wave && current(get())) {
        get().toggle()
        return
      }
      engine.ensureGraph()
      set({ buffering: true })
      try {
        let list = (await api.wave(get().waveSettings, getRegion())).filter((t) => !isHidden(t))
        if (!list.length) list = await api.top('')
        if (get().shuffle) set({ shuffle: false })
        get().playList(list, 0, { kind: 'wave', title: 'Моя волна', href: '/' })
      } catch (e) {
        set({ buffering: false })
        toast((e as Error).message)
      }
    },

    setWaveSettings: async (w) => {
      saveWaveSettings(w)
      set({ waveSettings: w })
      const s = get()
      if (!s.wave || !current(s)) return
      try {
        const list = (await api.wave(w, getRegion())).filter((t) => !isHidden(t))
        // Пока ждали ответ, настройки могли поменять ещё раз — тогда перестроит последний вызов.
        if (!list.length || get().waveSettings !== w || !get().wave) return
        get().replaceUpcoming(list)
        toast('Волна перестроена')
      } catch (e) {
        toast((e as Error).message)
      }
    },

    startRadio: async (t) => {
      try {
        const list = (await api.radio(t)).filter((x) => x.id !== t.id && !isHidden(x))
        if (!list.length) return toast('Для этого трека радио не нашлось')
        const source: Source = { kind: 'radio', title: `Радио «${t.title}»` }
        // Трек уже играет: он доигрывает, а дальше идёт радио.
        if (current(get())?.id === t.id) {
          if (get().shuffle) set({ shuffle: false, unshuffled: null })
          get().replaceUpcoming(list, source)
        } else {
          if (get().shuffle) set({ shuffle: false })
          get().playList([plain(t), ...list], 0, source)
        }
        toast(`Радио «${t.title}»`)
      } catch (e) {
        toast((e as Error).message || 'Не получилось включить радио')
      }
    },

    startArtistRadio: async ({ id, name, href }) => {
      engine.ensureGraph()
      try {
        const list = (await api.artistRadio(id ? { artistId: id } : { artistName: name })).filter((x) => !isHidden(x))
        if (!list.length) return toast('Радио не нашлось')
        if (get().shuffle) set({ shuffle: false })
        get().playList(list, 0, { kind: 'radio', title: `Радио: ${name}`, href })
      } catch (e) {
        toast((e as Error).message || 'Не получилось включить радио')
      }
    },

    replaceUpcoming: (list, source) => {
      const s = get()
      const cur = current(s)
      if (!cur) return
      const kept = s.queue.slice(0, s.index + 1)
      const have = new Set(kept.map((t) => t.id))
      const fresh = dedupe(list).filter((t) => !have.has(t.id))
      // Одной заменой: если убирать по одному, пустое «Далее» догрузило бы старую волну.
      set({
        queue: [...kept, ...fresh],
        queued: 0,
        unshuffled: null,
        ...(source ? { source, wave: source.kind === 'wave' } : {}),
      })
      preloadedFor = ''
      prefetchedFor = ''
    },

    removeUpcoming: (pred) => {
      const s = get()
      if (s.index < 0) return
      const ahead = s.queue.slice(s.index + 1)
      const userPart = ahead.slice(0, s.queued).filter((t) => !pred(t))
      const rest = ahead.slice(s.queued).filter((t) => !pred(t))
      if (userPart.length + rest.length === ahead.length) return
      set({ queue: [...s.queue.slice(0, s.index + 1), ...userPart, ...rest], queued: userPart.length })
      preloadedFor = ''
    },

    skipCurrent: () => {
      const s = get()
      if (s.index + 1 < s.queue.length) return go(s.index + 1)
      if (s.wave || s.source?.kind === 'radio') {
        void extend().then(() => {
          if (get().index + 1 < get().queue.length) go(get().index + 1)
        })
        return
      }
      engine.pause()
    },

    toggle: () => {
      const t = current(get())
      if (!t) return
      if (engine.paused) get().play()
      else get().pause()
    },
    play: () => {
      if (!current(get())) return
      if (!engine.el.src) return load(true, get().time)
      engine.play()
    },
    pause: () => engine.pause(),

    next: (user = false) => {
      const s = get()
      const cur = current(s)
      if (user && cur && !counted && engine.time < 20 && Date.now() - startedAt > 1500) {
        let rebuild = false
        if (s.wave) {
          skipsInRow++
          rebuild = skipsInRow >= 2
          if (rebuild) skipsInRow = 0
        }
        // Сначала пропуск доходит до сервера, потом свежая волна учитывает его.
        api
          .skip(cur)
          .catch(() => null)
          .then(() => {
            if (rebuild) void rebuildWaveAhead()
          })
      }
      if (!user && s.repeat === 'one' && cur) {
        finishListen()
        engine.seek(0)
        engine.play()
        return
      }
      const n = nextIndex()
      if (n === null) {
        if (s.wave || s.source?.kind === 'radio') {
          void extend().then(() => {
            if (get().index + 1 < get().queue.length) go(get().index + 1)
          })
          return
        }
        engine.pause()
        engine.seek(0)
        return
      }
      go(n)
    },

    prev: () => {
      const s = get()
      if (engine.time > 3 || s.index <= 0) {
        engine.seek(0)
        if (engine.paused) engine.play()
        return
      }
      go(s.index - 1)
    },

    jumpTo: (i) => {
      if (i < 0 || i >= get().queue.length) return
      set({ queued: 0 })
      go(i)
    },

    seek: (t) => {
      engine.seek(t)
      set({ time: t })
    },

    setVolume: (v) => {
      engine.setVolume(v)
      engine.setMuted(false)
      set({ volume: v, muted: false })
    },
    toggleMute: () => {
      const m = !get().muted
      engine.setMuted(m)
      set({ muted: m })
    },

    toggleShuffle: () => {
      const s = get()
      const cur = current(s)
      if (!s.shuffle) {
        if (!cur) return set({ shuffle: true })
        const rest = s.queue.filter((_, i) => i !== s.index)
        set({ shuffle: true, unshuffled: s.queue, queue: [cur, ...shuffled(rest)], index: 0, queued: 0 })
        toast('Перемешивание включено')
      } else {
        const orig = s.unshuffled ?? s.queue
        const keep = new Set(orig.map((t) => t.id))
        // Добавленное в очередь после перемешивания не теряем.
        const extra = s.queue.filter((t) => !keep.has(t.id))
        const queue = [...orig, ...extra]
        set({ shuffle: false, unshuffled: null, queue, index: cur ? queue.findIndex((t) => t.id === cur.id) : -1, queued: 0 })
        toast('Перемешивание выключено')
      }
    },

    cycleRepeat: () => {
      const r: Repeat = get().repeat === 'off' ? 'all' : get().repeat === 'all' ? 'one' : 'off'
      set({ repeat: r })
      toast(r === 'off' ? 'Повтор выключен' : r === 'all' ? 'Повтор очереди' : 'Повтор трека')
    },

    playNext: (t) => {
      const s = get()
      if (!current(s)) return get().playList([t])
      const q = s.queue.filter((x, i) => x.id !== t.id || i === s.index)
      const idx = q.findIndex((x) => x.id === current(s)!.id)
      q.splice(idx + 1, 0, t)
      set({ queue: q, index: idx, queued: s.queued + 1 })
      toast(`«${t.title}» заиграет следующим`)
    },

    addToQueue: (tt) => {
      const list = Array.isArray(tt) ? tt : [tt]
      const s = get()
      if (!current(s)) return get().playList(list)
      const ids = new Set(list.map((t) => t.id))
      const cur = current(s)!
      const q = s.queue.filter((x, i) => !ids.has(x.id) || i === s.index)
      const idx = q.findIndex((x) => x.id === cur.id)
      const at = Math.min(q.length, idx + 1 + s.queued)
      q.splice(at, 0, ...list.filter((t) => t.id !== cur.id))
      set({ queue: q, index: idx, queued: s.queued + list.length })
      toast(list.length === 1 ? `«${list[0].title}» добавлен в очередь` : `В очередь добавлено: ${list.length}`)
    },

    removeAt: (i) => {
      const s = get()
      if (i === s.index || i < 0 || i >= s.queue.length) return
      const q = s.queue.slice()
      q.splice(i, 1)
      set({
        queue: q,
        index: i < s.index ? s.index - 1 : s.index,
        queued: i > s.index && i <= s.index + s.queued ? s.queued - 1 : s.queued,
      })
    },

    move: (from, to) => {
      const s = get()
      if (from === to) return
      const q = s.queue.slice()
      const [it] = q.splice(from, 1)
      q.splice(to, 0, it)
      const cur = current(s)
      set({ queue: q, index: cur ? q.indexOf(cur) : s.index })
    },

    clearUpcoming: () => {
      const s = get()
      // Волна и радио после «Очистить» больше не догружаются: человек сам решил, что играть дальше.
      const source = s.source?.kind === 'radio' ? { ...s.source, kind: 'list' as const } : s.source
      set({ queue: s.queue.slice(0, s.index + 1), queued: 0, wave: false, source })
    },

    setEq: (p) => {
      const eq = { ...get().eq, ...p }
      set({ eq })
      engine.setEq(eq)
    },
    setCrossfade: (crossfade) => {
      if (crossfade > 0) engine.ensureGraph(true)
      set({ crossfade })
    },
    setNormalize: (normalize) => {
      engine.setNormalize(normalize)
      set({ normalize })
      if (normalize) askLoudness()
    },
    setRate: (rate) => {
      engine.setRate(rate)
      set({ rate })
    },
    setSleep: (min) => {
      if (min === null) set({ sleepAt: null, sleepEndOfTrack: false })
      else if (min === 'track') set({ sleepAt: null, sleepEndOfTrack: true })
      else set({ sleepAt: Date.now() + min * 60_000, sleepEndOfTrack: false })
      toast(min === null ? 'Таймер сна выключен' : min === 'track' ? 'Остановлю после этого трека' : `Остановлю через ${min} мин`)
    },
  }
})

// ───────────── ровная громкость ─────────────

engine.setLevelProvider((t) => levelOf(t.id))
engine.setNormalize(usePlayer.getState().normalize, false)

let lastLoudnessAsk = 0
/** Громкость текущего и трёх следующих треков; неизвестные сервер посчитает, спросим ещё раз позже. */
function askLoudness() {
  const s = usePlayer.getState()
  if (!s.normalize || s.index < 0) return
  lastLoudnessAsk = Date.now()
  const ids = s.queue.slice(s.index, s.index + 4).map((t) => t.id)
  if (s.repeat === 'all' && s.queue.length) ids.push(s.queue[0].id)
  requestLoudness(ids, () => engine.refreshLevels())
}

// ───────────── события движка ─────────────

let lastPos = 0
engine.setHandlers({
  time: (t, d) => {
    const s = usePlayer.getState()
    const cur = current(s)
    // У mp3 из запасных источников браузер часто оценивает длину наугад (VBR без оглавления).
    const sub = s.substitute && s.substitute.id === cur?.id && s.substitute.duration > 0 ? s.substitute.duration : 0
    usePlayer.setState({ time: t, duration: sub || (Number.isFinite(d) && d > 0 ? d : s.duration) })
    if (!cur) return
    // Прослушивание: считаем только плавный ход вперёд, перемотка и паузы не в счёт.
    if (listen?.track.id !== cur.id) {
      finishListen()
      listen = { track: cur, at: Math.floor(Date.now() / 1000), secs: 0, pos: t, dur: d > 0 ? d : cur.duration }
    } else {
      const step = t - listen.pos
      if (step > 0 && step < 2.5 && !engine.paused) listen.secs += step / (s.rate || 1)
      listen.pos = t
      if (d > 0) listen.dur = d
    }
    if (!counted && (enough(listen) || (d > 0 && t >= d / 2 && listen.secs >= 10))) {
      counted = true
      skipsInRow = 0
    }
    const left = d > 0 ? d - t : Infinity
    const nIdx = s.index + 1 < s.queue.length ? s.index + 1 : s.repeat === 'all' ? 0 : -1
    const nxt = nIdx >= 0 && s.repeat !== 'one' ? s.queue[nIdx] : null
    if (nxt && left < 30 && preloadedFor !== cur.id) {
      preloadedFor = cur.id
      engine.preload(nxt)
    }
    if (Date.now() - lastLoudnessAsk > 5000) askLoudness()
    if (nxt && s.crossfade > 0 && left <= s.crossfade && left > 0.4 && fadedFrom !== cur.id && !s.sleepEndOfTrack) {
      fadedFrom = cur.id
      if (engine.crossfadeTo(nxt, Math.min(s.crossfade, left))) {
        finishListen()
        counted = false
        startedAt = Date.now()
        preloadedFor = ''
        usePlayer.setState({ index: nIdx, queued: Math.max(0, s.queued - 1) })
        updateMediaSession(nxt)
      }
    }
    if ((s.wave || s.source?.kind === 'radio') && s.index >= s.queue.length - 3 && Date.now() - lastExtend > 20_000) void extendQueue()
    if (s.sleepAt && Date.now() >= s.sleepAt) {
      engine.pause()
      usePlayer.setState({ sleepAt: null })
      toast('Таймер сна: музыка на паузе')
    }
    if ('mediaSession' in navigator && d > 0 && Math.abs(t - lastPos) > 1) {
      lastPos = t
      try {
        navigator.mediaSession.setPositionState({ duration: d, position: Math.min(t, d), playbackRate: s.rate })
      } catch {
        /* noop */
      }
    }
  },
  state: (playing) => {
    usePlayer.setState({ playing })
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = playing ? 'playing' : 'paused'
  },
  buffering: (buffering) => usePlayer.setState({ buffering }),
  started: (t) => {
    errorsInRow = 0
    askLoudness()
    askSubstitute(t)
    if (prefetchedFor === t.id) return
    prefetchedFor = t.id
    const s = usePlayer.getState()
    const ids = s.queue.slice(s.index + 1, s.index + 3).map((x) => x.id)
    if (ids.length) api.prefetch(ids).catch(() => null)
  },
  ended: () => {
    const s = usePlayer.getState()
    if (s.sleepEndOfTrack) {
      usePlayer.setState({ sleepEndOfTrack: false, playing: false })
      toast('Таймер сна: трек доиграл, музыка на паузе')
      return
    }
    s.next(false)
  },
  error: (t) => {
    errorsInRow++
    const s = usePlayer.getState()
    usePlayer.setState({ buffering: false })
    if (errorsInRow > 3) {
      toast('Несколько треков подряд не загрузились. Попробуй позже')
      usePlayer.setState({ playing: false })
      return
    }
    toast(t ? `Не получилось включить «${t.title}», включаю следующий` : 'Трек не загрузился')
    s.next(false)
  },
})

// ───────────── Media Session: экран блокировки, наушники ─────────────

function updateMediaSession(t: Track) {
  if (!('mediaSession' in navigator)) return
  const art = bigThumb(t.thumb, 512)
  navigator.mediaSession.metadata = new MediaMetadata({
    title: t.title,
    artist: t.artist,
    album: t.album ?? '',
    artwork: art ? [{ src: art, sizes: '512x512' }] : [],
  })
  document.title = `${t.title} · ${t.artist}`
}

if ('mediaSession' in navigator) {
  const ms = navigator.mediaSession
  const s = () => usePlayer.getState()
  const h: [MediaSessionAction, MediaSessionActionHandler][] = [
    ['play', () => s().play()],
    ['pause', () => s().pause()],
    ['nexttrack', () => s().next(true)],
    ['previoustrack', () => s().prev()],
    ['seekto', (d) => d.seekTime !== undefined && s().seek(d.seekTime)],
    ['seekbackward', (d) => s().seek(Math.max(0, engine.time - (d.seekOffset || 10)))],
    ['seekforward', (d) => s().seek(engine.time + (d.seekOffset || 10))],
  ]
  for (const [a, fn] of h) {
    try {
      ms.setActionHandler(a, fn)
    } catch {
      /* не поддерживается */
    }
  }
}

// ───────────── сохранение между перезагрузками ─────────────

interface Saved {
  queue: Track[]
  index: number
  time: number
  volume: number
  muted: boolean
  shuffle: boolean
  repeat: Repeat
  rate: number
  source: Source | null
  wave: boolean
  crossfade: number
  normalize: boolean
  /** Ровная громкость с явным выбором. До версии с громкостью трека с сервера было только `normalize`
   *  (компрессор, по умолчанию выключен), поэтому его `false` не считаем выбором пользователя. */
  level?: boolean
  eq: EqState
  unshuffled: Track[] | null
}

export function restorePlayer() {
  let saved: Partial<Saved> | null = null
  try {
    saved = JSON.parse(localStorage.getItem(SAVE_KEY) || 'null')
  } catch {
    saved = null
  }
  if (saved) {
    const eq = { ...defaultEq, ...(saved.eq || {}) }
    usePlayer.setState({
      queue: saved.queue || [],
      index: saved.index ?? -1,
      time: saved.time || 0,
      volume: saved.volume ?? 0.85,
      muted: !!saved.muted,
      shuffle: !!saved.shuffle,
      repeat: saved.repeat || 'off',
      rate: saved.rate || 1,
      source: saved.source || null,
      wave: !!saved.wave,
      crossfade: saved.crossfade || 0,
      normalize: saved.level ?? (saved.normalize ? true : !isIOS),
      eq,
      unshuffled: saved.unshuffled || null,
    })
    engine.setVolume(saved.volume ?? 0.85)
    engine.setMuted(!!saved.muted)
    if (saved.rate && saved.rate !== 1) engine.setRate(saved.rate)
    if (eq.on) engine.setEq(eq)
    engine.setNormalize(usePlayer.getState().normalize, false)
    const t = current(usePlayer.getState())
    if (t) {
      engine.load(t, { autoplay: false, startAt: saved.time || 0 })
      updateMediaSession(t)
      counted = true
    }
  } else {
    engine.setVolume(0.85)
  }

  let timer = 0
  const save = () => {
    const s = usePlayer.getState()
    const data: Saved = {
      queue: s.queue.slice(Math.max(0, s.index - 50), s.index + 150),
      index: Math.min(s.index, 50),
      time: s.time,
      volume: s.volume,
      muted: s.muted,
      shuffle: s.shuffle,
      repeat: s.repeat,
      rate: s.rate,
      source: s.source,
      wave: s.wave,
      crossfade: s.crossfade,
      normalize: s.normalize,
      level: s.normalize,
      eq: s.eq,
      unshuffled: s.unshuffled ? s.unshuffled.slice(0, 300) : null,
    }
    try {
      localStorage.setItem(SAVE_KEY, JSON.stringify(data))
    } catch {
      /* переполнено */
    }
  }
  usePlayer.subscribe(() => {
    if (timer) return
    timer = window.setTimeout(() => {
      timer = 0
      save()
    }, 2000)
  })
  window.addEventListener('pagehide', () => {
    save()
    // Вкладку закрывают на середине трека: слушанное всё равно уходит в историю.
    finishListen(true)
  })
}
