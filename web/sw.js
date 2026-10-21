/* phone-bridge service worker：收推送、点按钮直接批准、点通知开 App */
const DB = 'pb'
const STORE = 'kv'

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))

self.addEventListener('push', (event) => {
  let d = {}
  try {
    d = event.data ? event.data.json() : {}
  } catch {
    d = { body: event.data ? event.data.text() : '' }
  }
  const actions = Array.isArray(d.actions) ? d.actions.slice(0, 2) : undefined
  const opts = {
    body: d.body || '',
    icon: '/icon-192.png',
    badge: '/icon-192.png',
    tag: d.tag || 'pb',
    renotify: Boolean(d.tag),
    data: d.data || {},
    vibrate: [80, 40, 80],
  }
  if (actions) opts.actions = actions
  if (d.data?.kind === 'approval') opts.requireInteraction = true
  event.waitUntil(self.registration.showNotification(d.title || '手机桥', opts))
})

self.addEventListener('notificationclick', (event) => {
  const note = event.notification
  const data = note.data || {}
  note.close()
  if ((event.action === 'allow' || event.action === 'deny') && data.kind === 'approval') {
    event.waitUntil(answer(data.id, event.action))
    return
  }
  event.waitUntil(focusApp())
})

async function focusApp() {
  const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
  for (const c of all) {
    if (String(c.url).startsWith(self.location.origin)) {
      try {
        return await c.focus()
      } catch {}
    }
  }
  return self.clients.openWindow('/')
}

async function answer(id, action) {
  const token = await idbGet('token')
  if (!token) return focusApp()
  let ok = false
  try {
    const r = await fetch('/api/answer', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer ' + token },
      body: JSON.stringify({ id, action }),
    })
    const j = await r.json()
    ok = Boolean(j && j.ok)
  } catch {}
  await self.registration.showNotification(ok ? (action === 'allow' ? '已批准 ✓' : '已拒绝') : '没处理成（可能已在电脑上处理）', {
    tag: 'pb-ack',
    silent: true,
    body: '手机桥',
  })
  const all = await self.clients.matchAll({ type: 'window' })
  for (const c of all) c.postMessage({ type: 'refresh' })
}

function idbGet(key) {
  return new Promise((resolve) => {
    const req = indexedDB.open(DB, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE)
    req.onerror = () => resolve(null)
    req.onsuccess = () => {
      try {
        const tx = req.result.transaction(STORE, 'readonly')
        const g = tx.objectStore(STORE).get(key)
        g.onsuccess = () => resolve(g.result || null)
        g.onerror = () => resolve(null)
      } catch {
        resolve(null)
      }
    }
  })
}
