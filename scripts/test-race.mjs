/**
 * 审批/提问的「双通道竞速」单元测试（不需要真 harness）。
 * 跑：node scripts/test-race.mjs
 */
import assert from 'node:assert'
import { Bridge } from '../host/bridge.js'

const store = {
  config: { approvalViaPhone: true, questionViaPhone: true, pushTurnEnd: false, pushErrors: false, snippetChars: 100, minTurnMs: 0 },
  target: () => null,
  setTarget() {},
  devices: () => [],
}
const sent = []
const pusher = { publicKey: 'k', lastError: null, subscribed: () => [], send: async (p) => { sent.push(p); return { sent: 1 } } }
const ctx = { get: () => undefined, on: () => () => {} }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const bridge = new Bridge(ctx, store, pusher)
const req = { agent: { id: 's1' }, toolName: 'bash', reason: 'rm -rf build', displayReason: { en: 'wipe build dir' } }
const pendingId = () => [...bridge.pending.keys()][0]

// 1) 手机先答：应返回手机的决定，且不再等桌面
{
  const p = bridge.onApproval({ ...req, callId: 'c1' }, async () => { await sleep(800); return 'unavailable' })
  await sleep(30)
  const id = pendingId()
  assert.ok(id, '应该有 pending')
  assert.equal(sent.length, 1, '应该推了一张卡')
  assert.equal(sent[0].actions[0].action, 'allow')
  const r = bridge.answer(id, { action: 'deny' })
  assert.equal(r.outcome, 'rejected')
  assert.equal(await p, 'rejected', '手机拒绝应生效')
  assert.equal(bridge.pending.size, 0, 'pending 应清空')
}

// 2) 桌面先答：应返回桌面结果，手机卡失效
{
  const p = bridge.onApproval({ ...req, callId: 'c2' }, async () => { await sleep(50); return 'allowed-once' })
  await sleep(10)
  const id = pendingId()
  assert.equal(await p, 'allowed-once', '应沿用桌面决定')
  await sleep(10)
  assert.equal(bridge.pending.size, 0, '桌面答完应清掉手机卡')
  assert.equal(bridge.answer(id, { action: 'allow' }).ok, false, '迟到的手机点击应被拒')
}

// 3) 手机慢于桌面但更快于桌面阻塞：手机赢
{
  const p = bridge.onApproval({ ...req, callId: 'c3' }, async () => { await sleep(900); return 'unavailable' })
  await sleep(20)
  setTimeout(() => bridge.answer(pendingId(), { action: 'allow' }), 40)
  assert.equal(await p, 'allowed-once')
}

// 4) 批准框被取消（signal abort）：卡标失效，最终用桌面结果
{
  const ac = new AbortController()
  const p = bridge.onApproval({ ...req, callId: 'c4', signal: ac.signal }, async () => { await sleep(60); return 'cancelled' })
  await sleep(20)
  ac.abort()
  await sleep(10)
  assert.equal(bridge.pending.size, 0, 'abort 后卡片应移除（不留残影）')
  assert.equal(await p, 'cancelled')
}

// 5) 提问转发
{
  const p = bridge.onQuestion(
    { agent: { id: 's1' }, questions: [{ id: 'q1', question: '用哪个方案？', options: [{ label: 'A' }, { label: 'B' }] }] },
    async () => { await sleep(500); return { answers: [{ id: 'q1', selected: ['桌面选的'] }] } },
  )
  await sleep(20)
  const id = pendingId()
  assert.equal(sent.at(-1).data.kind, 'question', '提问应推送')
  bridge.answer(id, { answers: [{ id: 'q1', selected: ['B'] }] })
  assert.deepEqual((await p).answers, [{ id: 'q1', selected: ['B'] }])
}

console.log('✓ 竞速 / 失效 / 提问 全部通过')

// 6) 发消息的占位消息必须在真实事件到达时被替换（否则手机上是两条）
{
  const promptCalls = []
  const ctxSay = {
    get: (k) => {
      if (k === 'sessionController') return { prompt: async (req) => { promptCalls.push(req); return { accepted: true } } }
      if (k === 'agents') return { get: () => ({ id: 's2' }) }
      if (k === 'sessions') return { get: () => undefined }
      return undefined
    },
    on: () => () => {},
  }
  const b2 = new Bridge(ctxSay, store, pusher)
  const r = await b2.say({ text: '你好', sessionId: 's2' })
  assert.equal(r.ok, true)
  assert.equal(promptCalls.length, 1, '应调用一次 prompt')
  assert.deepEqual(promptCalls[0].content, [{ type: 'text', text: '你好' }], 'content 形状要对')
  let msgs = b2.recent.get('s2') || []
  assert.equal(msgs.length, 1, '先有 1 条占位')
  assert.equal(msgs[0].pending, true)

  b2.onSessionEvent({ id: 's2' }, { type: 'user/message', seq: 7, time: Date.now(), data: { content: [{ type: 'text', text: '你好' }] } })
  msgs = b2.recent.get('s2') || []
  assert.equal(msgs.length, 1, '真实事件到达后仍然只有 1 条（不重复）')
  assert.equal(msgs[0].pending, undefined)
  assert.equal(msgs[0].seq, 7)
}

console.log('✓ 发消息不重复 / content 形状 通过')

// 7) DSH 每轮注入的上下文（source.kind != 'user'）不能当成他发的消息
{
  const ctx3 = { get: (k) => (k === 'agents' ? { get: () => ({ id: 's3' }) } : undefined), on: () => () => {} }
  const b3 = new Bridge(ctx3, store, pusher)
  const push = (data, seq, type = 'user/message') =>
    b3.onSessionEvent({ id: 's3' }, { type, seq, time: Date.now(), data })

  push({ source: { kind: 'time-context' }, content: [{ type: 'text', text: 'TIME CTX' }] }, 1)
  push({ source: { kind: 'runtime-context' }, content: [{ type: 'text', text: 'RUNTIME CTX' }] }, 2)
  push({ source: { kind: 'user' }, content: [{ type: 'text', text: '你好' }] }, 3)

  let rows = b3.recent.get('s3') || []
  assert.equal(rows.length, 1, '注入块不该进消息流')
  assert.equal(rows[0].text, '你好')

  push({ message: { content: [{ type: 'text', text: '回答' }] } }, 4, 'assistant/message')
  rows = b3.recent.get('s3')
  const last = rows[rows.length - 1]
  assert.equal(last.role, 'assistant')
  assert.equal(last.text, '回答')
  assert.ok(last.ctx && last.ctx.includes('TIME CTX') && last.ctx.includes('RUNTIME CTX'), '注入块应挂到这条回答上')

  push({ message: { content: [{ type: 'text', text: '回答2' }] } }, 5, 'assistant/message')
  assert.equal((b3.recent.get('s3').at(-1).ctx), undefined, '下一轮没有注入就不该有 ctx')
}

console.log('✓ 注入上下文折进思考过程 通过')

// 8) 历史会话列表 + 历史消息回放
{
  const ctx4 = {
    get: (k) => {
      if (k === 'sessionController')
        return {
          list: async () => ({
            items: [
              { sessionId: 'cold-1', cwd: '/Users/x/old-project', updatedAt: 100, running: false, projections: { values: { title: '旧会话' } } },
              { sessionId: 'cold-2', cwd: '/Users/x/another', updatedAt: 50, running: false },
            ],
          }),
          inspect: async () => ({
            events: [
              { type: 'user/message', seq: 1, time: 1, data: { source: { kind: 'user' }, content: [{ type: 'text', text: '老问题' }] } },
              { type: 'user/message', seq: 2, time: 2, data: { source: { kind: 'time-context' }, content: [{ type: 'text', text: 'TIME CTX' }] } },
              { type: 'assistant/message', seq: 3, time: 3, data: { message: { content: [{ type: 'text', text: '老回答' }] } } },
            ],
          }),
        }
      return undefined
    },
    on: () => () => {},
  }
  const b4 = new Bridge(ctx4, store, pusher)
  const list = await b4.sessionsWithHistory()
  assert.equal(list.length, 2, '两个历史会话都要出现')
  assert.equal(list.find((r) => r.id === 'cold-1').label, '旧会话', '标题要用 projection 里的 title')
  assert.equal(list.find((r) => r.id === 'cold-2').label, 'another', '没有标题就退回目录名')

  const msgs = await b4.messagesFor('cold-1')
  assert.equal(msgs.length, 2, '只回放真用户消息和回答')
  assert.deepEqual(msgs.map((m) => m.role), ['user', 'assistant'])
  assert.ok(msgs[1].ctx.includes('TIME CTX'), '历史里的注入块也要折进 ctx')
}

console.log('✓ 历史会话与历史消息回放 通过')
