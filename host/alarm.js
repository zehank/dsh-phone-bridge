/**
 * phone-bridge / alarm —— 「这个会话跑完，叫我」。
 *
 * 他不知道任务要跑多久，所以响铃不是定时器：指定一个会话，等它从 running 掉回
 * 空闲（或者报错）的那一刻才响。一次一响，响完自动撤掉，不用他记得去关。
 *
 * 三条通道同时走（手机在口袋里、锁屏也照样能被叫醒）：
 *   1. Web Push —— 页面可以关掉。响一次不够，按 alarmRepeatMs 重复推，
 *      每次 renotify 重新出声、重新振动，直到他按「停止」。
 *   2. SSE → 页面 —— 页面开着就本地循环响铃（真正像闹钟的那部分）。
 *   3. 点通知/重开页面 → /api/state 里 ringing 仍然是 true，接着响。
 */
import { randomBytes } from 'node:crypto'

const uid = () => randomBytes(4).toString('hex')

function fmtDur(ms) {
  const s = Math.max(0, Math.round(Number(ms || 0) / 1000))
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const r = s % 60
  if (m < 60) return r ? `${m} 分 ${r} 秒` : `${m} 分钟`
  const h = Math.floor(m / 60)
  return `${h} 小时 ${m % 60} 分`
}

export class Alarm {
  /**
   * @param {object} p
   * @param {object} p.store   phone-bridge 的 Store（配置 + alarm.json）
   * @param {object} p.pusher  Pusher（Web Push）
   * @param {() => void} p.broadcast  让 SSE 页面重取快照
   */
  constructor({ ctx, store, pusher, broadcast }) {
    this.ctx = ctx
    this.store = store
    this.pusher = pusher
    this.broadcast = broadcast || (() => {})
    this.repTimer = null
    this.reps = 0
    this.sessionId = null
    this.label = ''
    this.armedAt = 0
    this.ringing = false
    this.rangAt = 0
    this.lastReason = ''
    this.lastElapsed = 0
    this.#restore()
  }

  #restore() {
    const saved = this.store.load('alarm.json', null)
    if (!saved || !saved.sessionId || !saved.armedAt) return
    // 重启后接着等；但「正在响」不跨重启（那一声已经错过了）
    this.sessionId = saved.sessionId
    this.label = String(saved.label || '')
    this.armedAt = Number(saved.armedAt) || 0
  }

  #persist() {
    if (this.sessionId && this.armedAt) {
      this.store.save('alarm.json', { sessionId: this.sessionId, label: this.label, armedAt: this.armedAt })
    } else {
      this.store.save('alarm.json', {})
    }
  }

  status() {
    return {
      armed: Boolean(this.sessionId),
      sessionId: this.sessionId,
      label: this.label,
      armedAt: this.armedAt,
      ringing: this.ringing,
      rangAt: this.rangAt,
      reason: this.lastReason,
      elapsed: this.lastElapsed,
      reps: this.reps,
    }
  }

  /** 指定一个会话：它下次从 running 掉下来就响。 */
  arm(sessionId, label) {
    if (!sessionId) return { ok: false, error: '没有指定会话' }
    const same = this.sessionId === sessionId
    this.sessionId = sessionId
    // 同一个会话重复按就当「重新计一次」，别丢 arm 时间
    if (!same) this.armedAt = Date.now()
    this.label = String(label || this.label || '')
    if (this.ringing) this.#stopRinging()
    this.#persist()
    this.broadcast()
    return { ok: true, alarm: this.status() }
  }

  disarm() {
    this.sessionId = null
    this.label = ''
    this.armedAt = 0
    if (this.ringing) this.#stopRinging()
    this.#persist()
    this.broadcast()
    return { ok: true, alarm: this.status() }
  }

  /** 会话跑完（agent/status → 非 running）。不是等的那个会话就什么都不做。 */
  onIdle(sessionId, elapsedMs) {
    if (!this.sessionId || this.sessionId !== sessionId || this.ringing) return false
    return this.#fire({ reason: 'done', elapsedMs })
  }

  /** 跑挂了也叫人：错误比完成更该马上知道。 */
  onError(sessionId, message) {
    if (!this.sessionId || this.sessionId !== sessionId || this.ringing) return false
    return this.#fire({ reason: 'error', elapsedMs: this.armedAt ? Date.now() - this.armedAt : 0, message })
  }

  /** 测试：立刻按当前指定（或传进来的）会话响一次。 */
  test(sessionId, label) {
    if (sessionId) {
      this.sessionId = sessionId
      this.label = String(label || this.label || '')
      this.armedAt = this.armedAt || Date.now()
    }
    return this.#fire({ reason: 'test', elapsedMs: this.armedAt ? Date.now() - this.armedAt : 0 })
  }

  stop(by = 'phone') {
    if (!this.ringing) return { ok: true, ringing: false }
    const sid = this.sessionId
    this.#stopRinging()
    // 响铃是一次性的：停掉就撤掉指定，免得下一轮又响一次
    this.sessionId = null
    this.armedAt = 0
    this.#persist()
    this.broadcast()
    this.ctx?.logger?.info?.(`phone-bridge: 响铃已停（${by}${sid ? ' · ' + sid : ''}）`)
    return { ok: true, ringing: false }
  }

  dispose() {
    this.#clearTimer()
  }

  /* ── 内部 ───────────────────────────────────────────────── */

  #fire({ reason = 'done', elapsedMs = 0, message = '' } = {}) {
    const cfg = this.store.reloadConfig?.() || this.store.config || {}
    const sid = this.sessionId
    const label = this.label || 'DSH'
    this.ringing = true
    this.rangAt = Date.now()
    this.lastReason = reason
    this.lastElapsed = elapsedMs
    this.reps = 0
    // 点开手机页就是刚跑完的那个会话，不用再手动切
    try { if (sid) this.store.setTarget(sid) } catch {}
    this.broadcast()

    const title =
      reason === 'error' ? `⏰ ${label} · 跑挂了` : reason === 'test' ? `⏰ ${label} · 测试响铃` : `⏰ ${label} · 跑完了`
    const body =
      reason === 'error'
        ? String(message || '出错了').slice(0, 180)
        : `${fmtDur(elapsedMs)} · 点开这份通知按「停止」`

    this.#push({ title, body, reason })
    const every = Math.max(1000, Number(cfg.alarmRepeatMs ?? 20000))
    const maxReps = Math.max(0, Number(cfg.alarmMaxReps ?? 6))
    if (cfg.alarmPushes !== false && maxReps > 0) {
      this.#clearTimer()
      this.repTimer = setInterval(() => {
        if (!this.ringing) return this.#clearTimer()
        if (this.reps >= maxReps) return this.#clearTimer()
        this.reps += 1
        this.#push({ title, body, reason, repeat: this.reps })
      }, every)
      this.repTimer.unref?.()
    }
    return { ok: true, ringing: true, sessionId: sid }
  }

  #push({ title, body, reason, repeat = 0 }) {
    if (!this.pusher?.send) return
    if (this.store.config?.pushEnabled === false) return
    this.pusher
      .send({
        title,
        body,
        tag: 'pb-alarm',
        requireInteraction: true,
        renotify: true,
        vibrate: [900, 400, 900, 400, 900, 400, 900],
        data: { kind: 'alarm', reason, repeat, at: Date.now() },
        actions: [{ action: 'stop', title: '停止响铃' }],
      })
      .catch(() => {})
  }

  #stopRinging() {
    this.ringing = false
    this.#clearTimer()
    // 让手机上挂着的那条通知自己收掉（SW 收到 alarm-off 就 close）
    if (this.pusher?.send) {
      this.pusher
        .send({ title: '', body: '', tag: 'pb-alarm', silent: true, data: { kind: 'alarm-off', at: Date.now() } })
        .catch(() => {})
    }
    this.broadcast()
  }

  #clearTimer() {
    if (this.repTimer) clearInterval(this.repTimer)
    this.repTimer = null
  }
}

export { uid, fmtDur }
