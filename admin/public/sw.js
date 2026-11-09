const CACHE_NAME = 'tg-admin-shell-v2';
const SHELL = ['/offline', '/manifest.webmanifest', '/icons/tg-admin.svg'];
self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  // Never cache admin pages or API responses: they may contain private account data.
  if (url.pathname.startsWith('/api/') || request.mode === 'navigate') {
    if (request.mode === 'navigate') {
      event.respondWith(fetch(request).catch(async () => (await caches.match('/offline')) || Response.error()));
    }
    return;
  }
  if (url.pathname.startsWith('/_next/static/') || url.pathname.startsWith('/icons/') || url.pathname === '/manifest.webmanifest') {
    event.respondWith(caches.match(request).then((cached) => cached || fetch(request).then((response) => {
      if (response.ok) { const copy = response.clone(); caches.open(CACHE_NAME).then((cache) => cache.put(request, copy)); }
      return response;
    })));
  }
});