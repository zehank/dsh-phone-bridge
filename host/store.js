/**
 * phone-bridge / store —— 磁盘状态（配置、配对码、已配对设备、VAPID 密钥）。
 *
 * 数据目录：~/.dsh/phone-bridge/
 *   config.json  可手改的配置（端口、公网地址、开关）
 *   state.json   配对码 + 设备列表 + 当前目标会话
 *   vapid.json   Web Push 的 VAPID 密钥对（首次运行生成，丢了要重新订阅）
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto'

export const DIR = process.env.DSH_PHONE_BRIDGE_HOME || path.join(os.homedir(), '.dsh', 'phone-bridge')

export const DEFAULTS = {
  enabled: true,
  /** 本机监听端口；Tailscale Serve 会把它代理成 https://<机器>.<tailnet>.ts.net/ */
  port: 8791,
  /** 手机要打开的地址；留空则只在内网页面上显示提示 */
  publicUrl: '',
  pushEnabled: true,
  approvalViaPhone: true,
  questionViaPhone: true,
  pushTurnEnd: true,
  pushErrors: true,
  /** 推送到手机上的回复片段长度 */
  snippetChars: 220,
  /** 短于这个时长的回合不推送（免得一句话也弹一次） */
  minTurnMs: 4000,
}

function code6() {
  return String(randomInt(0, 1_000_000)).padStart(6, '0')
}

function readJson(file, fallback) {
  try {
    const raw = fs.readFileSync(path.join(DIR, file), 'utf8')
    return JSON.parse(raw)
  } catch {
    return fallback
  }
}

export class Store {
  constructor() {
    this.dir = DIR
    try {
      fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 })
    } catch {}
    this.config = { ...DEFAULTS, ...readJson('config.json', {}) }
    const st = readJson('state.json', {})
    this.state = {
      code: typeof st.code === 'string' && /^\d{6}$/.test(st.code) ? st.code : code6(),
      devices: Array.isArray(st.devices) ? st.devices : [],
      targetSessionId: typeof st.targetSessionId === 'string' ? st.targetSessionId : null,
    }
    this.fails = []
    this.persistState()
    this.persistConfig()
  }

  /** 每次管理页读盘重载，改了 config.json 不用重启。 */
  reloadConfig() {
    this.config = { ...DEFAULTS, ...readJson('config.json', {}) }
    return this.config
  }

  persistConfig() {
    try {
      const p = path.join(DIR, 'config.json')
      if (!fs.existsSync(p)) fs.writeFileSync(p, JSON.stringify(this.config, null, 2), { mode: 0o600 })
    } catch {}
  }

  persistState() {
    try {
      fs.writeFileSync(path.join(DIR, 'state.json'), JSON.stringify(this.state, null, 2), { mode: 0o600 })
    } catch {}
  }

  devices() {
    return this.state.devices
  }

  device(token) {
    if (!token) return undefined
    return this.state.devices.find((d) => d.token === token)
  }

  code() {
    return this.state.code
  }

  rotateCode() {
    this.state.code = code6()
    this.persistState()
    return this.state.code
  }

  /** 配对码比对（限速：10 分钟内最多 10 次失败）。 */
  checkCode(input) {
    const now = Date.now()
    this.fails = this.fails.filter((t) => now - t < 10 * 60_000)
    if (this.fails.length >= 10) return { ok: false, error: '尝试次数过多，稍后再试' }
    const want = Buffer.from(String(this.state.code))
    const got = Buffer.from(String(input || '').trim())
    const same = want.length === got.length && timingSafeEqual(want, got)
    if (!same) {
      this.fails.push(now)
      return { ok: false, error: '配对码不对' }
    }
    return { ok: true }
  }

  addDevice(name, sub) {
    const token = randomBytes(24).toString('base64url')
    const dev = {
      token,
      name: String(name || '手机').slice(0, 40),
      createdAt: new Date().toISOString(),
      sub: sub || null,
    }
    this.state.devices.push(dev)
    this.persistState()
    return dev
  }

  setSub(token, sub) {
    const d = this.device(token)
    if (!d) return false
    d.sub = sub || null
    this.persistState()
    return true
  }

  dropSub(token) {
    const d = this.device(token)
    if (!d) return false
    d.sub = null
    this.persistState()
    return true
  }

  removeDevice(token) {
    const before = this.state.devices.length
    this.state.devices = this.state.devices.filter((d) => d.token !== token)
    this.persistState()
    return this.state.devices.length !== before
  }

  target() {
    return this.state.targetSessionId
  }

  setTarget(id) {
    this.state.targetSessionId = id || null
    this.persistState()
    return this.state.targetSessionId
  }

  load(file, fallback) {
    return readJson(file, fallback)
  }

  save(file, value) {
    try {
      fs.writeFileSync(path.join(DIR, file), JSON.stringify(value, null, 2), { mode: 0o600 })
      return true
    } catch {
      return false
    }
  }
}
