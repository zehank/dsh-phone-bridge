/**
 * phone-bridge / server —— 本机 HTTP 服务（只绑 127.0.0.1，由 Tailscale Serve 转成 HTTPS）。
 *
 *   /                 手机打开的是 PWA；本机打开的是配置页（含配对码 + 二维码）
 *   /sw.js  /manifest.webmanifest  /icon-*.png    PWA 资源
 *   /api/pair         用配对码换设备 token
 *   /api/state        快照（会话 / 待办 / 最近消息）
 *   /api/stream       SSE，状态一变推一帧
 *   /api/say          手机下命令
 *   /api/answer       手机批准 / 回答
 *   /api/subscribe    存 Web Push 订阅
 *   /api/admin/*      仅限 127.0.0.1：配对码、设备、测试推送
 */
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import QRCode from 'qrcode'
import { tailscaleInfo, enableServe } from './tailscale.js'

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
}

const LOCAL = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])

/** 插件版本：页面拿它和自己嵌的 BUILD 比对，不一致就自动重载（否则会一直跑旧 JS） */
function readBuild(root) {
  try {
    const pkg = JSON.parse(fs.readFileSync(path.join(root, '..', 'package.json'), 'utf8'))
    return String(pkg.version || '0')
  } catch {
    return '0'
  }
}

/** 装到主屏后两台电脑的图标靠这个名字区分 */
export const HOST_NAME = String(os.hostname() || 'dsh').replace(/\.local$/i, '')

/**
 * 本机请求才给管理页。注意 Tailscale Serve 会把手机的请求也从 127.0.0.1 转进来，
 * 所以带转发头（X-Forwarded-For / Tailscale-*）的一律不算本机，否则手机能直接看到配对码。
 */
function isLocalReq(req) {
  if (!LOCAL.has(String(req.socket.remoteAddress || ''))) return false
  const h = req.headers || {}
  if (h['x-forwarded-for'] || h['x-real-ip'] || h.forwarded) return false
  for (const k of Object.keys(h)) if (k.toLowerCase().startsWith('tailscale-')) return false
  return true
}

function json(res, code, obj) {
  const body = JSON.stringify(obj)
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

function readBody(req) {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (c) => {
      raw += c
      if (raw.length > 1 << 20) req.destroy()
    })
    req.on('end', () => {
      try {
        resolve(JSON.parse(raw || '{}'))
      } catch {
        resolve({})
      }
    })
    req.on('error', () => resolve({}))
  })
}

export function createServer({ ctx, store, bridge, pusher, webRoot }) {
  const BUILD = readBuild(webRoot)
  const port = () => Number(store.config.port) || 8791
  const localUrl = () => `http://127.0.0.1:${port()}/`
  const publicUrl = () => String(store.reloadConfig().publicUrl || '').trim()

  function tokenOf(req, url) {
    const auth = String(req.headers.authorization || '')
    if (auth.toLowerCase().startsWith('bearer ')) return auth.slice(7).trim()
    return url.searchParams.get('t') || ''
  }

  function serveFile(res, file) {
    try {
      const abs = path.join(webRoot, file)
      const data = fs.readFileSync(abs)
      res.writeHead(200, {
        'content-type': MIME[path.extname(abs)] || 'application/octet-stream',
        'cache-control': file === 'sw.js' ? 'no-cache' : 'public, max-age=300',
        'content-length': data.length,
      })
      res.end(data)
      return true
    } catch {
      return false
    }
  }

  async function adminState() {
    const ts = await tailscaleInfo()
    const url = publicUrl() || ts.url
    let qr = ''
    if (url) {
      try {
        qr = await QRCode.toString(url, { type: 'svg', margin: 1, width: 220, errorCorrectionLevel: 'M' })
      } catch {}
    }
    return {
      ok: true,
      url,
      localUrl: localUrl(),
      qr,
      code: store.code(),
      port: port(),
      devices: store.devices().map((d) => ({
        token: d.token.slice(0, 6) + '…',
        name: d.name,
        createdAt: d.createdAt,
        subscribed: !!d.sub?.endpoint,
      })),
      push: {
        enabled: !!store.config.pushEnabled,
        subscribed: pusher.subscribed().length,
        lastError: pusher.lastError,
      },
      sessions: bridge.liveSessions(),
      client: store.load('diag.json', null),
      build: BUILD,
      approvals: store.load('approvals.json', []),
      tailscale: {
        dnsName: ts.dnsName,
        url: ts.url,
        serve: ts.serve,
        serveKnown: ts.serveKnown,
        detail: ts.detail,
        raw: ts.raw,
        error: ts.error,
        configuredUrl: publicUrl(),
        hint: publicUrl() || ts.url
          ? ''
          : ts.error === 'cli-unavailable'
            ? 'App 里调不动 Tailscale CLI（The GUI failed to start），把手动地址填到下面就行。'
            : 'Tailscale 没连上：先打开 Tailscale App 并登录这台电脑。',
      },
    }
  }

  async function adminPage() {
    const st = await adminState()
    let tpl = ''
    try {
      tpl = fs.readFileSync(path.join(webRoot, 'admin.html'), 'utf8')
    } catch {
      return '<!doctype html><meta charset="utf-8"><p>admin.html 丢了</p>'
    }
    return tpl.replace('/*__BOOT__*/', `window.__ADMIN__ = ${JSON.stringify(st).replace(/</g, '\\u003c')};`)
  }

  function phonePage() {
    try {
      return fs
        .readFileSync(path.join(webRoot, 'phone.html'), 'utf8')
        .replaceAll('__HOST__', HOST_NAME)
        .replaceAll('__BUILD__', BUILD)
    } catch {
      return '<!doctype html><meta charset="utf-8"><p>phone.html 丢了</p>'
    }
  }

  async function api(req, res, url, pathname) {
    const isLocal = isLocalReq(req)
    const token = tokenOf(req, url)
    const device = store.device(token)

    /* 配对：唯一一个不需要 token 的接口 */
    if (pathname === '/api/pair' && req.method === 'POST') {
      const body = await readBody(req)
      const check = store.checkCode(body.code)
      if (!check.ok) return json(res, 200, { ok: false, error: check.error })
      const dev = store.addDevice(body.name || '手机')
      ctx.logger?.info?.(`phone-bridge: 新设备已配对 ${dev.name}`)
      return json(res, 200, { ok: true, token: dev.token, vapid: pusher.publicKey })
    }

    /* 本机配置页接口 */
    if (pathname.startsWith('/api/admin/')) {
      if (!isLocal) return json(res, 403, { ok: false, error: '仅限本机' })
      if (pathname === '/api/admin/state') return json(res, 200, await adminState())
      if (pathname === '/api/admin/code' && req.method === 'POST') {
        store.rotateCode()
        return json(res, 200, await adminState())
      }
      if (pathname === '/api/admin/remove' && req.method === 'POST') {
        const body = await readBody(req)
        const target = store.devices().find((d) => d.token.startsWith(String(body.prefix || '')))
        if (target) store.removeDevice(target.token)
        return json(res, 200, await adminState())
      }
      if (pathname === '/api/admin/url' && req.method === 'POST') {
        const body = await readBody(req)
        const next = String(body.url || '').trim()
        if (next && !/^https:\/\/[^\s/]+\/$/.test(next)) {
          return json(res, 200, { ok: false, error: '要 https://机器名.ts.net/ 这种形式（末尾带 /）' })
        }
        store.save('config.json', { ...store.reloadConfig(), publicUrl: next })
        store.reloadConfig()
        return json(res, 200, { ok: true, state: await adminState() })
      }
      if (pathname === '/api/admin/tailscale' && req.method === 'POST') {
        const r = await enableServe(port())
        return json(res, 200, { ...r, state: await adminState() })
      }
      if (pathname === '/api/admin/test' && req.method === 'POST') {
        const r = await pusher.send({
          title: '手机桥 · 测试',
          body: '能看到这条就说明推送链路通了。',
          tag: 'pb-test',
          data: { kind: 'test' },
        })
        return json(res, 200, { ok: r.sent > 0, ...r })
      }
      return json(res, 404, { ok: false, error: 'no route' })
    }

    if (!device) return json(res, 401, { ok: false, error: '未配对' })

    if (pathname === '/api/state') return json(res, 200, { ...bridge.snapshot(device.name), build: BUILD })

    if (pathname === '/api/stream') {
      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache, no-transform',
        connection: 'keep-alive',
        'x-accel-buffering': 'no',
      })
      res.write('retry: 3000\n\n')
      const off = bridge.subscribe(res)
      const beat = setInterval(() => {
        try {
          res.write(': ping\n\n')
        } catch {}
      }, 25000)
      const done = () => {
        clearInterval(beat)
        off()
      }
      req.on('close', done)
      res.on('close', done)
      return
    }

    if (pathname === '/api/say' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        const r = await bridge.say({ text: body.text, sessionId: body.sessionId, mode: body.mode })
        return json(res, 200, r)
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/upload' && req.method === 'POST') {
      const uploads = ctx.get?.('fileUploads')
      if (!uploads?.uploadStream) return json(res, 200, { ok: false, error: '这个宿主没有 fileUploads' })
      const sid = url.searchParams.get('sessionId') || bridge.targetSession()
      if (!sid) return json(res, 200, { ok: false, error: '没有可用会话' })
      const name = String(url.searchParams.get('name') || 'file').slice(0, 120)
      const chunks = []
      let total = 0
      let tooBig = false
      await new Promise((resolve) => {
        req.on('data', (c) => {
          total += c.length
          if (total > 25 << 20) {
            tooBig = true
            req.destroy()
          } else chunks.push(c)
        })
        req.on('end', resolve)
        req.on('error', resolve)
      })
      if (tooBig) return json(res, 200, { ok: false, error: '文件太大（25MB 上限）' })
      try {
        const data = (async function* () {
          yield Buffer.concat(chunks)
        })()
        const value = await uploads.uploadStream({ sessionId: sid, data, name })
        ctx.logger?.info?.(`phone-bridge: 收到文件 ${name} ${total}B`)
        return json(res, 200, { ok: true, receiptId: value.receiptId, file: value.file })
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/models') {
      try {
        return json(res, 200, await bridge.models())
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/model' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        return json(res, 200, await bridge.selectModel(body))
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/permission' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        return json(res, 200, bridge.setPermission(body))
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/presets') {
      try {
        return json(res, 200, await bridge.presets())
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e), presets: [] })
      }
    }

    if (pathname === '/api/preset' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        return json(res, 200, await bridge.selectPreset(body))
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/stats') {
      const force = url.searchParams.get('full') === '1'
      const stats = bridge.statsOf(bridge.targetSession())
      const balance = await bridge.balance(force)
      return json(res, 200, { ok: true, stats, balance })
    }

    if (pathname === '/api/workspaces') {
      return json(res, 200, { ok: true, list: bridge.workspaces(), cwd: bridge.currentCwd() })
    }

    if (pathname === '/api/cwd' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        return json(res, 200, await bridge.setCwd(body))
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/session' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        return json(res, 200, await bridge.newSession(body))
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/schedules') {
      try {
        return json(res, 200, { ok: true, list: await bridge.schedules() })
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e), list: [] })
      }
    }

    if (pathname === '/api/schedule' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        return json(res, 200, await bridge.createSchedule(body))
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/schedule/delete' && req.method === 'POST') {
      const body = await readBody(req)
      try {
        return json(res, 200, await bridge.deleteSchedule(body))
      } catch (e) {
        return json(res, 200, { ok: false, error: String(e?.message || e) })
      }
    }

    if (pathname === '/api/stop' && req.method === 'POST') {
      const body = await readBody(req)
      return json(res, 200, await bridge.stop(body.sessionId))
    }

    if (pathname === '/api/target' && req.method === 'POST') {
      const body = await readBody(req)
      bridge.setTarget(body.sessionId)
      return json(res, 200, bridge.snapshot(device.name))
    }

    if (pathname === '/api/answer' && req.method === 'POST') {
      const body = await readBody(req)
      const r = bridge.answer(body.id, body)
      ctx.logger?.info?.(`phone-bridge: ${device.name} 处理 ${body.id} → ${JSON.stringify(r)}`)
      return json(res, 200, r)
    }

    if (pathname === '/api/diag' && req.method === 'POST') {
      const body = await readBody(req)
      const rec = { ...body, device: device.name, receivedAt: Date.now() }
      store.save('diag.json', rec)
      ctx.logger?.info?.(`phone-bridge: 手机上报 ${JSON.stringify(rec).slice(0, 300)}`)
      return json(res, 200, { ok: true })
    }

    if (pathname === '/api/subscribe' && req.method === 'POST') {
      const body = await readBody(req)
      const ok = store.setSub(device.token, body.subscription)
      ctx.logger?.info?.(`phone-bridge: ${device.name} 订阅${ok ? '成功' : '失败'}`)
      return json(res, 200, { ok })
    }

    if (pathname === '/api/unsubscribe' && req.method === 'POST') {
      store.setSub(device.token, null)
      return json(res, 200, { ok: true })
    }

    if (pathname === '/api/push/test' && req.method === 'POST') {
      const r = await pusher.send({ title: '手机桥 · 测试', body: '推送通了。', tag: 'pb-test', data: { kind: 'test' } })
      return json(res, 200, { ok: r.sent > 0, ...r })
    }

    if (pathname === '/api/logout' && req.method === 'POST') {
      store.removeDevice(device.token)
      return json(res, 200, { ok: true })
    }

    return json(res, 404, { ok: false, error: 'no route' })
  }

  async function handler(req, res) {
    const url = new URL(String(req.url || '/'), 'http://localhost')
    const pathname = decodeURIComponent(url.pathname)
    try {
      if (pathname.startsWith('/api/')) return await api(req, res, url, pathname)

      if (pathname === '/' || pathname === '/index.html') {
        const html = isLocalReq(req) && !store.device(tokenOf(req, url)) ? await adminPage() : phonePage()
        res.writeHead(200, {
          'content-type': 'text/html; charset=utf-8',
          'cache-control': 'no-store',
        })
        return res.end(html)
      }

      if (pathname === '/manifest.webmanifest') {
        try {
          const raw = fs.readFileSync(path.join(webRoot, 'manifest.webmanifest'), 'utf8')
          const body = raw.replaceAll('__HOST__', HOST_NAME)
          res.writeHead(200, {
            'content-type': MIME['.webmanifest'],
            'cache-control': 'no-store',
            'content-length': Buffer.byteLength(body),
          })
          return res.end(body)
        } catch {}
      }

      const file = pathname.replace(/^\/+/, '')
      if (/^[a-z0-9._-]+$/i.test(file) && serveFile(res, file)) return
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
      return res.end('not found')
    } catch (e) {
      try {
        json(res, 500, { ok: false, error: String(e?.message || e) })
      } catch {}
    }
  }

  let server = null

  return {
    start() {
      server = http.createServer(handler)
      server.on('error', (e) => {
        ctx.logger?.warn?.(`phone-bridge: 监听 ${port()} 失败 ${e?.message || e}`)
      })
      server.listen(port(), '127.0.0.1', () => {
        ctx.logger?.info?.(`phone-bridge: ${localUrl()}（手机走 ${publicUrl() || 'Tailscale 域名'}）`)
      })
      return server
    },
    stop() {
      try {
        server?.close()
      } catch {}
      server = null
    },
    localUrl,
  }
}
