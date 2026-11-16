// Сервис-воркер: оболочка приложения работает офлайн и открывается мгновенно.
// API и аудио не кэшируем — они всегда идут в сеть.
const CACHE = 'music-shell-v1'
const SCOPE = self.registration.scope

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll([SCOPE])).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (e) => {
  const req = e.request
  const url = new URL(req.url)
  if (req.method !== 'GET' || url.origin !== location.origin || url.pathname.includes('/api/')) return
  // Хешированные файлы сборки не меняются — сначала кэш.
  if (url.pathname.includes('/assets/') || url.pathname.includes('/icons/')) {
    e.respondWith(
      caches.match(req).then(
        (hit) =>
          hit ||
          fetch(req).then((res) => {
            if (res.ok) caches.open(CACHE).then((c) => c.put(req, res.clone()))
            return res
          }),
      ),
    )
    return
  }
  // Страницы — сначала сеть, без сети — сохранённая оболочка.
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req)
        .then((res) => {
          if (res.ok) caches.open(CACHE).then((c) => c.put(SCOPE, res.clone()))
          return res
        })
        .catch(() => caches.match(SCOPE)),
    )
  }
})
