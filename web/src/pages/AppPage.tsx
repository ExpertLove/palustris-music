import { ArrowLeft, Download } from 'lucide-react'
import { Link } from 'wouter'
import { Logo } from '../components/common'

/** Версия APK на /dl/muzyka.apk: менять вместе с android/app/build.gradle.kts и файлом на сервере. */
const APP = { version: '0.6.4', size: '4,4 МБ', date: '24 ноября 2026', url: '/dl/muzyka.apk' }

export default function AppPage() {
  return (
    <div className="legal app-page">
      <Link href="/" className="brand" style={{ padding: 0 }}>
        <Logo />
        <span>Музыка</span>
      </Link>

      <h1>Приложение для Android</h1>
      <p>
        Тот же аккаунт и та же библиотека, что на сайте, плюс то, чего браузер не умеет: фоновое воспроизведение, скачанные
        треки без сети, управление с экрана блокировки и наушников, виджет на главный экран и Android Auto.
      </p>

      <div className="app-dl">
        <div>
          <div className="app-dl-ver">Версия {APP.version}</div>
          <div className="app-dl-sub">
            {APP.date} · {APP.size} · Android 8 и новее
          </div>
        </div>
        <a className="btn btn-primary btn-lg" href={APP.url} download>
          <Download size={20} /> Скачать APK
        </a>
      </div>

      <h2>Что нового в {APP.version}</h2>
      <ul>
        <li>Кнопки «назад/вперёд» больше не пропадают на часах, в машине и в шторке.</li>
        <li>Обложки грузятся через наш сервер: без VPN работают у операторов, которые режут Google.</li>
      </ul>
      <p className="app-note">
        Если стоит 0.6.1 или старее — сначала удали старую версию: подпись изменилась в 0.6.2, поверх она не встанет.
        Аккаунт, лайки, плейлисты и история останутся на сервере — пропадут только настройки и скачанные в приложении треки.
      </p>

      <h2>Как установить</h2>
      <ol>
        <li>Скачай APK по кнопке выше.</li>
        <li>Открой файл. Если Android спросит — разреши установку из этого источника.</li>
        <li>Установи и войди тем же аккаунтом, что на сайте.</li>
      </ol>
      <p className="muted">
        Обновления выходят файлом по этой же ссылке. Скачивать можно только с music.palustris.ru — сторонние сборки не
        поддерживаются. Приложение не публикуется в Google Play.
      </p>

      <Link href="/" className="btn btn-outline" style={{ marginTop: 10 }}>
        <ArrowLeft size={18} /> На главную
      </Link>
    </div>
  )
}
