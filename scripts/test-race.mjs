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
