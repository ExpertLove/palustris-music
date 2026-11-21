import { ArrowRight, FileText, Heart, ListMusic, Radio, SlidersHorizontal, Smartphone } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'wouter'
import { Logo } from '../components/common'
import { Aura } from '../components/Shell'
import { applyPalette, inkFor } from '../lib/color'

/** Демо «плеера», который перекрашивается под обложку — главная идея дизайна. */
const DEMO = [
  { t: 'Ночная трасса', a: 'Подборка «Ночная»', d1: '#3b3a9e', d2: '#d8336f' },
  { t: 'Утро без спешки', a: 'Подборка «Утренняя»', d1: '#e0812f', d2: '#1690a8' },
  { t: 'Зелёная волна', a: 'Подборка «Инди»', d1: '#2c9a52', d2: '#c08a1c' },
  { t: 'Неон', a: 'Подборка «Электроника»', d1: '#7b47e8', d2: '#ff5470' },
]

export default function Landing() {
  const [i, setI] = useState(0)
  useEffect(() => {
    const id = window.setInterval(() => setI((x) => (x + 1) % DEMO.length), 4200)
    return () => window.clearInterval(id)
  }, [])
  const d = DEMO[i]
  useEffect(() => {
    applyPalette({ accent: d.d2, accentInk: inkFor(d.d2), aura1: d.d1, aura2: d.d2 })
  }, [d])

  return (
    <>
      <Aura strong />
      <div className="lp">
        <div className="lp-wrap">
          <nav className="lp-nav">
            <Link href="/" className="brand" style={{ padding: 0 }}>
              <Logo />
              <span>Музыка</span>
            </Link>
            <div style={{ display: 'flex', gap: 8 }}>
              <Link href="/login" className="btn btn-ghost btn-sm">
                Войти
              </Link>
              <Link href="/register" className="btn btn-light btn-sm">
                Создать аккаунт
              </Link>
            </div>
          </nav>

          <section className="lp-hero">
            <div>
              <h1 className="display">Музыка, которая подстраивается под тебя</h1>
              <p className="lead">
                Миллионы треков из каталога YouTube Music, «Моя волна», которая учится на твоих лайках, тексты песен и эквалайзер. Без рекламы, с синхронизацией между устройствами.
              </p>
              <div className="lp-cta">
                <Link href="/register" className="btn btn-primary btn-lg">
                  Начать слушать
                </Link>
                <Link href="/login" className="btn btn-outline btn-lg">
                  У меня есть аккаунт
                </Link>
              </div>
            </div>
            <div className="lp-demo" style={{ ['--d1' as string]: d.d1, ['--d2' as string]: d.d2 } as React.CSSProperties} aria-hidden>
              <div className="bgc" />
              <div className="art">
                <Logo className="" />
              </div>
              <div className="info">
                <div>
                  <div style={{ fontSize: 22, fontWeight: 760, letterSpacing: '-0.02em' }}>{d.t}</div>
                  <div className="muted">{d.a}</div>
                </div>
                <div className="bar">
                  <i key={i} />
                </div>
              </div>
            </div>
          </section>

          <section className="lp-section">
            <h2>Всё для того, чтобы слушать, а не искать</h2>
            <div className="lp-features">
              <div className="lp-f wide">
                <div className="ico">
                  <SlidersHorizontal size={22} />
                </div>
                <h3>Эквалайзер на 10 полос</h3>
                <p>Тяни кривую мышью или пальцем, выбирай пресеты: «Бас», «Рок», «Голос» и другие. Плюс ровная громкость и плавные переходы между треками.</p>
                <div className="lp-eq" aria-hidden>
                  {[0.9, 0.7, 0.55, 0.4, 0.35, 0.45, 0.6, 0.75, 0.85, 0.95].map((a, k) => (
                    <i key={k} style={{ ['--a' as string]: a, ['--b' as string]: 1.15 - a * 0.7, animationDelay: `${k * -0.21}s`, height: '100%' } as React.CSSProperties} />
                  ))}
                </div>
              </div>
              <div className="lp-f">
                <div className="ico">
                  <Radio size={22} />
                </div>
                <h3>Моя волна</h3>
                <p>Бесконечный поток под твой вкус. Лайки делают его точнее, пропуски убирают лишнее.</p>
              </div>
              <div className="lp-f">
                <div className="ico">
                  <FileText size={22} />
                </div>
                <h3>Тексты песен</h3>
                <p>Строки подсвечиваются в такт. Нажми на строку — и трек перемотается к ней.</p>
              </div>
              <div className="lp-f">
                <div className="ico">
                  <ListMusic size={22} />
                </div>
                <h3>Плейлисты и ссылки</h3>
                <p>Собирай свои подборки, меняй порядок перетаскиванием и делись ссылкой с друзьями.</p>
              </div>
              <div className="lp-f">
                <div className="ico">
                  <Smartphone size={22} />
                </div>
                <h3>Как приложение</h3>
                <p>С главного экрана — без установки, или APK для Android с фоном, офлайном и экраном блокировки.</p>
                <Link href="/app" className="btn btn-light btn-sm" style={{ marginTop: 'auto', justifySelf: 'start' }}>
                  Подробнее <ArrowRight size={16} />
                </Link>
              </div>
            </div>
          </section>

          <section className="lp-section lp-final">
            <Heart size={34} style={{ color: 'var(--accent)' }} fill="currentColor" />
            <h2>Включи первую песню через минуту</h2>
            <p className="muted" style={{ maxWidth: '46ch' }}>
              Регистрация — email и пароль. Выбери пару жанров, и «Моя волна» сразу знает, с чего начать.
            </p>
            <Link href="/register" className="btn btn-primary btn-lg">
              Создать аккаунт
            </Link>
          </section>

          <footer className="lp-foot">
            <span>Музыка · личный проект. Аудио — из каталога YouTube, права принадлежат авторам.</span>
            <span style={{ display: 'flex', gap: 18 }}>
              <Link href="/app">Приложение</Link>
              <Link href="/terms">Условия</Link>
              <Link href="/privacy">Конфиденциальность</Link>
            </span>
          </footer>
        </div>
      </div>
    </>
  )
}
