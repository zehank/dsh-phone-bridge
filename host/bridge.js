/**
 * phone-bridge / bridge —— DSH 与手机之间的中枢。
 *
 * 三个接点：
 *   1. 下命令  手机 → sessionController.prompt()（和 GUI 输入框回车是同一条路）
 *   2. 批操作  approval/request waterfall：手机和桌面 GUI 同时收到，谁先答用谁
 *   3. 弹消息  agent/status / session/event → Web Push
 *
 * 审批这里的关键是「竞速」而不是拦截：先调 next() 让桌面照常弹框，再和手机的
 * 决定赛跑。谁先回谁生效——否则他一坐在电脑前反而只能掏手机。
 */
import { randomBytes } from 'node:crypto'
import path from 'node:path'

const uid = () => randomBytes(6).toString('hex')

/** 把一条消息里的文字块拼出来（忽略 reasoning / 工具调用）。 */
export function textOf(message) {
  const content = message?.content
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  return content
    .filter((b) => b?.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('\n')
    .trim()
}

function labelOf(cwd) {
  if (!cwd) return ''
  const base = path.basename(String(cwd))
  return base || String(cwd)
}

export class Bridge {
  constructor(ctx, store, pusher) {
    this.ctx = ctx
    this.store = store
    this.pusher = pusher
    this.pending = new Map()
    this.recent = new Map()
    this.running = new Map()
    this.startedAt = new Map()
    this.labels = new Map()
    this.subscribers = new Set()
    this.lastActivity = new Map()
    this.stats = new Map()
    this.liveCwd = new Map()
    this.ctxBuf = new Map()
    this.approvalLog = []
    this.balanceCache = null
    this.balanceAt = 0
  }

  /* ── 生命周期 ───────────────────────────────────────────── */

  wire() {
    const ctx = this.ctx
    this.disposers = [
      ctx.on('approval/request', (req, next) => this.onApproval(req, next)),
      ctx.on('user-questions/request', (req, next) => this.onQuestion(req, next)),
      ctx.on('session/event', (session, event) => this.onSessionEvent(session, event)),
      ctx.on('agent/status', (payload) => this.onStatus(payload)),
      ctx.on('agent/error', (payload) => this.onError(payload)),
      ctx.on('agent/disposed', ({ agent }) => this.forget(agent?.id)),
    ]
  }

  dispose() {
    for (const off of this.disposers || []) {
      try {
        off?.()
      } catch {}
    }
    this.disposers = []
    this.subscribers.clear()
    this.pending.clear()
  }

  forget(sid) {
    if (!sid) return
    this.running.delete(sid)
    this.startedAt.delete(sid)
    this.lastActivity.delete(sid)
  }

  /* ── SSE 广播 ───────────────────────────────────────────── */

  subscribe(res) {
    this.subscribers.add(res)
    return () => this.subscribers.delete(res)
  }

  broadcast() {
    for (const res of this.subscribers) {
      try {
        res.write('event: state\ndata: {}\n\n')
      } catch {
        this.subscribers.delete(res)
      }
    }
  }

  /* ── 会话 ───────────────────────────────────────────────── */

  liveSessions() {
    const agents = this.ctx.get?.('agents')?.list?.() || []
    const titles = this.ctx.get?.('sessionTitle')
    const rows = []
    for (const a of agents) {
      const sid = a?.id
      if (!sid) continue
      const session = a?.session
      const cwd = session?.header?.cwd || session?.cwd || ''
      if (cwd) this.labels.set(sid, labelOf(cwd))
      let title = ''
      try {
        title = String(titles?.get?.(session)?.title || '').trim()
      } catch {}
      rows.push({
        id: sid,
        label: title || this.labels.get(sid) || String(sid).replace(/^session-/, '').slice(0, 8),
        cwd,
        running: this.running.has(sid),
        lastActivity: this.lastActivity.get(sid) || 0,
      })
    }
    // 刚新建、还没被 prompt 激活的会话也要能选
    for (const [sid, cwd] of this.liveCwd) {
      if (rows.some((r) => r.id === sid)) {
        this.liveCwd.delete(sid)
        continue
      }
      rows.push({ id: sid, label: '新会话', cwd, running: false, lastActivity: Date.now() })
    }
    return rows.sort((a, b) => b.lastActivity - a.lastActivity || String(a.id).localeCompare(String(b.id)))
  }

  targetSession() {
    const rows = this.liveSessions()
    const saved = this.store.target()
    if (saved && rows.some((r) => r.id === saved)) return saved
    return rows[0]?.id || saved || null
  }

  setTarget(sid) {
    try {
      this.store.setTarget(sid)
    } catch {}
    this.broadcast()
    return sid
  }

  /* ── 下命令 ─────────────────────────────────────────────── */

  async say({ text, sessionId, mode, files }) {
    const body = String(text || '').trim()
    const attachments = Array.isArray(files) ? files.filter((f) => f?.receiptId && f?.file) : []
    if (!body && !attachments.length) throw new Error('空消息')
    const sc = this.ctx.get?.('sessionController')
    if (!sc?.prompt) throw new Error('sessionController 不可用（宿主还没起来）')
    const sid = sessionId || this.targetSession()
    if (!sid) throw new Error('没有可用会话：先在 GUI 里开一个对话')

    const requestId = `pb-${uid()}`
    const content = []
    if (body) content.push({ type: 'text', text: body })
    for (const f of attachments) content.push({ type: 'file', attachment: f.file })

    // 附件先按 requestId 绑定，prompt 被接收后才 commit；失败要让绑定回滚
    let binding = null
    const uploads = this.ctx.get?.('fileUploads')
    const agent = this.ctx.get?.('agents')?.get?.(sid)
    if (attachments.length && uploads?.bindPrompt && agent) {
      binding = uploads.bindPrompt(agent, attachments.map((f) => f.receiptId), requestId)
    }

    try {
      await sc.prompt(
        {
          requestId,
          sessionId: sid,
          mode: mode === 'steer' ? 'steer' : 'queue',
          content,
          clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        },
        new AbortController().signal,
      )
      try {
        binding?.commit?.()
      } catch {}
    } catch (e) {
      try {
        binding?.[Symbol.dispose]?.()
      } catch {}
      throw e
    }

    this.setTarget(sid)
    // 占位消息：真实 user/message 到达时会被替换掉，避免手机上出现两条一样的
    this.pushRecent(sid, {
      role: 'user',
      text: body || attachments.map((f) => f.file?.name || '文件').join('、'),
      time: Date.now(),
      seq: -1,
      pending: true,
      files: attachments.map((f) => f.file?.name).filter(Boolean),
    })
    this.broadcast()
    return { ok: true, sessionId: sid }
  }

  /* ── 会话级设置：模型 / 权限 / Plan ───────────────────── */

  sessionOf(sid) {
    return this.ctx.get?.('sessions')?.get?.(sid)
  }

  agentOf(sid) {
    return this.ctx.get?.('agents')?.get?.(sid)
  }

  settings(sid) {
    const sessionId = sid || this.targetSession()
    if (!sessionId) return null
    const out = { sessionId, model: null, permission: null, plan: null, error: null }
    try {
      const session = this.sessionOf(sessionId)
      const agent = this.agentOf(sessionId)
      const cfg = session?.requestHeader?.()?.config
      if (cfg) out.model = { provider: cfg.provider, model: cfg.model, reasoningEffort: cfg.reasoningEffort || null }
      const pp = this.ctx.get?.('permissionPresets')
      if (pp && session) {
        const cat = pp.catalog()
        out.permission = { current: pp.current(session), options: cat?.options || [] }
      }
      const ap = this.ctx.get?.('agentPresets')
      if (ap) {
        // 当前「模式」= 会话日志里记的 preset；还没开始过回合就读活 agent 的 composition
        let current = session?.header?.agentPreset || null
        if (!current && agent) {
          try {
            current = ap.composedPreset?.(agent.ctx) || null
          } catch {}
        }
        out.mode = { current }
      }
    } catch (e) {
      out.error = String(e?.message || e)
    }
    return out
  }

  async models() {
    const sc = this.ctx.get?.('sessionController')
    if (!sc?.modelCatalog) throw new Error('modelCatalog 不可用')
    const cat = await sc.modelCatalog()
    const sid = this.targetSession()
    const cfg = this.sessionOf(sid)?.requestHeader?.()?.config
    return {
      ok: true,
      groups: cat?.groups || [],
      failures: cat?.failures || [],
      current: cfg ? { provider: cfg.provider, model: cfg.model, reasoningEffort: cfg.reasoningEffort || null } : null,
    }
  }

  async selectModel({ provider, model, reasoningEffort, sessionId }) {
    const sc = this.ctx.get?.('sessionController')
    const sid = sessionId || this.targetSession()
    if (!sc?.selectModel || !sid) throw new Error('没有可用会话')
    if (!provider || !model) throw new Error('缺 provider / model')
    await sc.selectModel({
      sessionId: sid,
      provider,
      model,
      ...(reasoningEffort ? { reasoningEffort } : {}),
    })
    this.broadcast()
    return { ok: true }
  }

  setPermission({ preset, sessionId }) {
    const pp = this.ctx.get?.('permissionPresets')
    const sid = sessionId || this.targetSession()
    const session = this.sessionOf(sid)
    if (!pp?.set || !session) throw new Error('权限预设不可用')
    pp.set(session, String(preset))
    this.broadcast()
    return { ok: true }
  }

  /** 模式 = agent preset（标准 / 创造 …） */
  async presets() {
    const ap = this.ctx.get?.('agentPresets')
    if (!ap?.remoteExportList) throw new Error('agentPresets 不可用')
    const roster = await ap.remoteExportList()
    const sid = this.targetSession()
    const session = this.sessionOf(sid)
    const agent = this.agentOf(sid)
    let current = session?.header?.agentPreset || null
    if (!current && agent) {
      try {
        current = ap.composedPreset?.(agent.ctx) || null
      } catch {}
    }
    return {
      ok: true,
      current,
      presets: (roster?.presets || []).map((p) => ({
        id: p.id,
        name: p.name || p.id,
        description: p.description || '',
        isDefault: !!p.isDefault,
        broken: p.broken || null,
      })),
    }
  }

  async selectPreset({ id, sessionId }) {
    const ap = this.ctx.get?.('agentPresets')
    const sid = sessionId || this.targetSession()
    const agent = this.agentOf(sid)
    if (!ap?.select) throw new Error('agentPresets 不可用')
    if (!agent) throw new Error('这个会话还没激活：先发一条消息，再切模式')
    if (!id) throw new Error('缺 preset id')
    const committed = await ap.select(agent, String(id))
    this.broadcast()
    return { ok: true, preset: committed || String(id) }
  }

  async stop(sessionId) {
    const sc = this.ctx.get?.('sessionController')
    const sid = sessionId || this.targetSession()
    if (!sc?.cancel || !sid) return { ok: false }
    sc.cancel({ sessionId: sid })
    this.broadcast()
    return { ok: true }
  }

  /* ── 待办（审批 / 提问） ────────────────────────────────── */

  addPending(entry) {
    const p = { ...entry, createdAt: Date.now() }
    p.promise = new Promise((resolve) => {
      p.resolve = resolve
    })
    this.pending.set(p.id, p)
    this.broadcast()
    return p
  }

  clearPending(id) {
    this.pending.delete(id)
    this.broadcast()
  }

  pendingList() {
    return [...this.pending.values()].map((p) => ({
      id: p.id,
      kind: p.kind,
      tool: p.tool || '',
      reason: p.reason || '',
      sessionId: p.sessionId || '',
      createdAt: p.createdAt,
      questions: p.questions || null,
      expired: !!p.expired,
    }))
  }

  /** 手机端点了按钮：返回给 waterfall 的最终值。 */
  answer(id, payload) {
    const p = this.pending.get(id)
    if (!p) return { ok: false, error: '这条已经处理过或已失效' }
    if (p.expired) return { ok: false, error: '已过期' }
    this.pending.delete(id)
    if (p.kind === 'approval') {
      const outcome = payload?.action === 'allow' ? 'allowed-once' : 'rejected'
      p.resolve(outcome)
      this.broadcast()
      return { ok: true, outcome }
    }
    const answers = Array.isArray(payload?.answers) ? payload.answers : []
    p.resolve({ answers })
    this.broadcast()
    return { ok: true }
  }

  /* ── 事件挂钩 ───────────────────────────────────────────── */

  async onApproval(req, next) {
    if (!this.store.config.approvalViaPhone) return next()
    const desktop = next()
    const sid = req?.agent?.id || ''
    const entry = this.addPending({
      id: `ap-${uid()}`,
      kind: 'approval',
      tool: req?.toolName || '工具',
      reason: this.reasonOf(req),
      sessionId: sid,
    })
    const body = [req?.toolName || '工具', this.reasonOf(req)].filter(Boolean).join('\n')
    this.pusher
      .send({
        title: '需要你批准',
        body: body.slice(0, 300),
        tag: `pb-${entry.id}`,
        actions: [
          { action: 'allow', title: '允许一次' },
          { action: 'deny', title: '拒绝' },
        ],
        data: { kind: 'approval', id: entry.id },
      })
      .catch(() => {})
    const onAbort = () => this.clearPending(entry.id)
    req?.signal?.addEventListener?.('abort', onAbort, { once: true })
    this.logApproval({ id: entry.id, callId: req?.callId || null, tool: entry.tool, sessionId: sid, stage: 'pushed' })
    const desktopTagged = desktop.then((v) => ({ by: 'desktop', v }), (e) => ({ by: 'desktop-error', e }))
    try {
      const first = await Promise.race([entry.promise.then((v) => ({ by: 'phone', v })), desktopTagged])
      this.logApproval({
        id: entry.id,
        callId: req?.callId || null,
        tool: entry.tool,
        sessionId: sid,
        stage: 'decided',
        by: first?.by,
        outcome: first?.v || (first?.e ? 'error' : null),
      })
      if (first?.by === 'phone') return first.v
      if (first?.by === 'desktop-error') throw first.e
      return first.v
    } finally {
      req?.signal?.removeEventListener?.('abort', onAbort)
      this.clearPending(entry.id)
    }
  }

  async onQuestion(req, next) {
    if (!this.store.config.questionViaPhone) return next()
    const desktop = next()
    const questions = (req?.questions || []).map((q) => ({
      id: q.id,
      question: q.question,
      detail: q.detail || '',
      header: q.header || '',
      multiSelect: !!q.multiSelect,
      options: (q.options || []).map((o) => ({ label: o.label, description: o.description || '' })),
    }))
    const first = questions[0]
    const entry = this.addPending({
      id: `uq-${uid()}`,
      kind: 'question',
      sessionId: req?.agent?.id || '',
      questions,
    })
    this.pusher
      .send({
        title: '等你回答',
        body: [first?.header, first?.question].filter(Boolean).join(' · ').slice(0, 300) || '有一组问题要你选',
        tag: `pb-${entry.id}`,
        data: { kind: 'question', id: entry.id },
      })
      .catch(() => {})
    const onAbort = () => this.clearPending(entry.id)
    req?.signal?.addEventListener?.('abort', onAbort, { once: true })
    try {
      return await Promise.race([entry.promise, desktop])
    } finally {
      req?.signal?.removeEventListener?.('abort', onAbort)
      this.clearPending(entry.id)
    }
  }

  /** 审批留痕：谁先答、什么时候、结果是什么，写进 approvals.json 给管理页看 */
  logApproval(entry) {
    this.approvalLog.push({ ...entry, at: Date.now() })
    if (this.approvalLog.length > 20) this.approvalLog.splice(0, this.approvalLog.length - 20)
    try {
      this.store.save('approvals.json', this.approvalLog.slice(-20))
    } catch {}
  }

  reasonOf(req) {
    const d = req?.displayReason
    if (d && typeof d === 'object') {
      return String(d.zh || d['zh-CN'] || d.en || '').trim()
    }
    return String(req?.reason || '').trim()
  }

  onSessionEvent(session, event) {
    const sid = session?.id || session?.meta?.id
    if (!sid || !event?.type) return
    const cwd = session?.header?.cwd || session?.cwd
    if (cwd) this.labels.set(sid, labelOf(cwd))
    if (event.type === 'assistant/message') {
      const text = textOf(event.data?.message)
      if (text) {
        const ctx = this.takeContext(sid)
        this.pushRecent(sid, { role: 'assistant', text, time: event.time || Date.now(), seq: event.seq, ...(ctx ? { ctx } : {}) })
      }
    } else if (event.type === 'user/message') {
      const src = event.data?.source?.kind || 'user'
      const text = textOf(event.data)
      if (src !== 'user') {
        // DSH 每轮会往会话里塞 time-context / runtime-context / dsh-mnemon 等注入块，
        // 它们也是 user/message，但不是他打的字 —— 收进「思考过程」里，不进消息流
        if (text) {
          this.noteContext(sid, text)
          this.broadcast()
        }
        return
      }
      if (text) {
        // 手机发出去时插了一条占位（pending），真实事件到了就把它摘掉，否则显示两条
        const rows = this.recent.get(sid) || []
        const at = rows.findIndex((m) => m.pending && (m.text === text || !m.text))
        if (at >= 0) rows.splice(at, 1)
        this.pushRecent(sid, { role: 'user', text, time: event.time || Date.now(), seq: event.seq })
      }
    } else if (event.type === 'turn/start') {
      this.statOf(sid).turns += 1
    } else if (event.type === 'step/start') {
      this.statOf(sid).steps += 1
    } else if (event.type === 'assistant/message') {
      this.addUsage(sid, event.data?.usage)
    } else {
      return
    }
    this.lastActivity.set(sid, Date.now())
    this.broadcast()
  }

  onStatus({ agent, status } = {}) {
    const sid = agent?.id
    if (!sid) return
    if (status === 'running') {
      this.running.set(sid, true)
      this.startedAt.set(sid, Date.now())
      this.lastActivity.set(sid, Date.now())
      this.broadcast()
      return
    }
    const started = this.startedAt.get(sid)
    this.running.delete(sid)
    this.startedAt.delete(sid)
    this.lastActivity.set(sid, Date.now())
    this.broadcast()
    if (!this.store.config.pushTurnEnd || !started) return
    const elapsed = Date.now() - started
    if (elapsed < (this.store.config.minTurnMs || 0)) return
    const last = [...(this.recent.get(sid) || [])].reverse().find((m) => m.role === 'assistant')
    const label = this.labels.get(sid) || 'DSH'
    const snip = (last?.text || '').replace(/\s+/g, ' ').trim().slice(0, this.store.config.snippetChars || 220)
    this.pusher
      .send({
        title: `${label} · 回完了（${Math.round(elapsed / 1000)}s）`,
        body: snip || '这一轮没有文字输出',
        tag: 'pb-turn',
        url: '/',
        data: { kind: 'turn' },
      })
      .catch(() => {})
  }

  onError({ agent, error } = {}) {
    if (!this.store.config.pushErrors || !agent?.id) return
    this.pusher
      .send({
        title: '出错了',
        body: String(error?.message || error || '未知错误').slice(0, 260),
        tag: 'pb-error',
        data: { kind: 'error' },
      })
      .catch(() => {})
  }

  noteContext(sid, text) {
    const rows = this.ctxBuf.get(sid) || []
    rows.push(String(text))
    let total = rows.join('\n\n').length
    while (total > 20000 && rows.length > 1) total -= rows.shift().length + 2
    this.ctxBuf.set(sid, rows)
  }

  takeContext(sid) {
    const rows = this.ctxBuf.get(sid) || []
    this.ctxBuf.delete(sid)
    const text = rows.join('\n\n').trim()
    if (!text) return ''
    return text.length > 12000 ? text.slice(0, 12000) + '…（已截断）' : text
  }

  pushRecent(sid, msg) {
    const rows = this.recent.get(sid) || []
    rows.push(msg)
    if (rows.length > 80) rows.splice(0, rows.length - 80)
    this.recent.set(sid, rows)
  }

  /* ── 用量统计（轮 / 步 / token / 缓存 / 上下文 / 余额） ──── */

  statOf(sid) {
    let s = this.stats.get(sid)
    if (!s) {
      s = { turns: 0, steps: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0, reasoning: 0, lastInput: 0, seeded: false }
      this.stats.set(sid, s)
    }
    return s
  }

  addUsage(sid, usage) {
    if (!usage) return
    const s = this.statOf(sid)
    s.input += Number(usage.inputTokens || 0)
    s.output += Number(usage.outputTokens || 0)
    s.cacheRead += Number(usage.cacheReadTokens || 0)
    s.cacheWrite += Number(usage.cacheWriteTokens || 0)
    s.reasoning += Number(usage.reasoningTokens || 0)
    if (usage.inputTokens) s.lastInput = Number(usage.inputTokens)
  }

  /** 第一次看这个会话时，从会话日志里补出历史总数，之后靠事件增量维护。 */
  seedStats(sid) {
    const s = this.statOf(sid)
    if (s.seeded) return s
    s.seeded = true
    try {
      const session = this.sessionOf(sid)
      if (!session?.snapshotEvents) return s
      const events = session.snapshotEvents(0, session.seq) || []
      for (const ev of events) {
        if (ev?.type === 'turn/start') s.turns += 1
        else if (ev?.type === 'step/start') s.steps += 1
        else if (ev?.type === 'assistant/message') this.addUsage(sid, ev.data?.usage)
      }
    } catch {}
    return s
  }

  statsOf(sid) {
    const id = sid || this.targetSession()
    if (!id) return null
    const s = this.seedStats(id)
    let contextWindow = 0
    try {
      contextWindow = Number(this.sessionOf(id)?.requestContext?.()?.contextWindow || 0)
    } catch {}
    const cacheTotal = s.cacheRead + s.input
    return {
      sessionId: id,
      turns: s.turns,
      steps: s.steps,
      input: s.input,
      output: s.output,
      cacheRead: s.cacheRead,
      cacheWrite: s.cacheWrite,
      reasoning: s.reasoning,
      lastInput: s.lastInput,
      contextWindow,
      contextUsed: contextWindow && s.lastInput ? s.lastInput / contextWindow : 0,
      cacheHitRate: cacheTotal ? s.cacheRead / cacheTotal : 0,
    }
  }

  /** 余额：走 Platform 查询，缓存 60 秒，失败就返回 null（不阻塞页面）。 */
  async balance(force = false) {
    if (!force && this.balanceCache && Date.now() - this.balanceAt < 60_000) return this.balanceCache
    const acct = this.ctx.get?.('deepseekAccount')
    if (!acct?.getBalance) return null
    try {
      const out = await acct.getBalance({
        version: 'phone-bridge',
        locale: 'zh-CN',
        timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
      })
      const cache = { ok: true, wallets: [], bonusWallets: [] }
      if (out?.status === 'ready') {
        cache.wallets = (out.value || []).map((w) => ({ currency: w.currency, balance: w.balance }))
        cache.bonusWallets = (out.bonusWallets || []).map((w) => ({ currency: w.currency, balance: w.balance }))
      } else if (out?.status === 'failed') {
        cache.ok = false
        cache.error = '查询失败'
      } else {
        cache.ok = false
        cache.error = '未登录'
      }
      this.balanceCache = cache
      this.balanceAt = Date.now()
      return cache
    } catch (e) {
      this.balanceCache = { ok: false, error: String(e?.message || e) }
      this.balanceAt = Date.now()
      return this.balanceCache
    }
  }

  /* ── 工作区 / 新会话 / 定时任务 ─────────────────────────── */

  workspaces() {
    try {
      const reg = this.ctx.get?.('workspaceRegistry')
      const rows = reg?.list?.() || []
      return rows.map((w) => ({ id: w.id, path: w.path || '', title: w.title || path.basename(String(w.path || '')) || '工作区' }))
    } catch {
      return []
    }
  }

  currentCwd(sid) {
    const id = sid || this.targetSession()
    try {
      const session = this.sessionOf(id)
      return session?.header?.cwd || this.liveSessions().find((r) => r.id === id)?.cwd || ''
    } catch {
      return ''
    }
  }

  async setCwd({ path: dir, sessionId }) {
    const wd = this.ctx.get?.('workingDirectory')
    const agent = this.agentOf(sessionId || this.targetSession())
    if (!wd?.set || !agent) throw new Error('没有可用会话')
    const next = String(dir || '').trim()
    if (!next) throw new Error('路径为空')
    const real = await wd.set(agent, next, new AbortController().signal)
    this.broadcast()
    return { ok: true, path: real || next }
  }

  async newSession({ cwd, workspaceId }) {
    const sc = this.ctx.get?.('sessionController')
    if (!sc?.create) throw new Error('sessionController 不可用')
    const value = await sc.create({
      ...(workspaceId ? { workspaceId } : {}),
      ...(cwd ? { cwd } : {}),
    })
    const sid = value?.sessionId
    if (!sid) throw new Error('创建失败')
    this.store.setTarget(sid)
    this.liveCwd.set(sid, cwd || '')
    this.broadcast()
    return { ok: true, sessionId: sid }
  }

  async schedules() {
    const sch = this.ctx.get?.('schedule')
    if (!sch?.catalog) throw new Error('schedule 服务不可用')
    const rows = await sch.catalog()
    return (rows || []).map((r) => ({
      id: r.id,
      sessionId: r.sessionId,
      title: r.title || '',
      prompt: r.prompt || '',
      kind: r.kind,
      status: r.status,
      scheduledAt: r.scheduledAt,
      timing:
        r.kind === 'daily'
          ? `每天 ${r.time}`
          : r.kind === 'every'
            ? `每 ${Math.round((r.everySeconds || 0) / 60)} 分钟`
            : r.kind === 'after'
              ? `${Math.round((r.afterSeconds || 0) / 60)} 分钟后`
              : r.kind === 'cron'
                ? r.expression
                : r.kind === 'weekly'
                  ? `每周 ${(r.weekdays || []).join(',')} ${r.time}`
                  : r.scheduledAt,
    }))
  }

  async createSchedule({ title, prompt, daily, everySeconds, sessionId }) {
    const sch = this.ctx.get?.('schedule')
    const sid = sessionId || this.targetSession()
    if (!sch?.create || !sid) throw new Error('没有可用会话')
    const t = String(title || '').trim()
    const p = String(prompt || '').trim()
    if (!t || !p) throw new Error('标题和内容都要填')
    const req = { title: t, prompt: p }
    if (daily?.time) req.daily = { time: String(daily.time).slice(0, 5) + ':00', time_zone: Intl.DateTimeFormat().resolvedOptions().timeZone }
    else req.every_seconds = Math.max(60, Math.round(Number(everySeconds || 3600)))
    const rec = await sch.create(sid, req, new AbortController().signal)
    this.broadcast()
    return { ok: true, id: rec?.id }
  }

  async deleteSchedule({ id, sessionId }) {
    const sch = this.ctx.get?.('schedule')
    const sid = sessionId || this.targetSession()
    if (!sch?.delete || !sid) throw new Error('没有可删除的任务')
    const r = await sch.delete({ sessionId: sid, id }, new AbortController().signal)
    this.broadcast()
    return { ok: true, deleted: !!r?.deleted }
  }

  /* ── 给手机的状态快照 ───────────────────────────────────── */

  snapshot(deviceName) {
    const sessions = this.liveSessions()
    const target = this.targetSession()
    const msgs = (this.recent.get(target) || []).slice(-40)
    return {
      ok: true,
      now: Date.now(),
      device: deviceName || '',
      sessions,
      target,
      pending: this.pendingList(),
      messages: msgs,
      settings: this.settings(target),
      stats: this.statsOf(target),
      cwd: this.currentCwd(target),
      workspaces: this.workspaces(),
      vapid: this.pusher.publicKey,
      push: {
        enabled: !!this.store.config.pushEnabled,
        subscribed: this.pusher.subscribed().length > 0,
        lastError: this.pusher.lastError || null,
      },
    }
  }
}
