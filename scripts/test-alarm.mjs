/**
 * 「跑完叫我」的单元测试（不需要真 harness，也不会真推手机）。
 * 跑：node scripts/test-alarm.mjs
 */
import assert from 'node:assert'
import { Alarm } from '../host/alarm.js'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function makeStore() {
  const mem = {}
  return {
    config: { pushEnabled: true, alarmRepeatMs: 1000, alarmMaxReps: 2, alarmPushes: true },
    reloadConfig() {
      return this.config
    },
    load: (f, d) => (f in mem ? mem[f] : d),
    save: (f, v) => {
      mem[f] = v
      return true
    },
    target: null,
    setTarget(v) {
      this.target = v
    },
    _mem: mem,
  }
}

const sent = []
const pusher = { send: async (p) => (sent.push(p), { sent: 1 }) }
const store = makeStore()
const alarm = new Alarm({ ctx: {}, store, pusher, broadcast: () => {} })

// 1) 没指定时什么都不响
assert.equal(alarm.onIdle('s-other', 9999), false)
assert.equal(alarm.status().ringing, false)
assert.equal(sent.length, 0)

// 2) 指定 s1
alarm.arm('s1', '简历重写')
assert.equal(alarm.status().armed, true)
assert.equal(alarm.status().label, '简历重写')
assert.equal(sent.length, 0, 'arm 本身不该响')

// 3) 别的会话跑完不影响
assert.equal(alarm.onIdle('s2', 1000), false)
assert.equal(sent.length, 0)

// 4) 指定的会话跑完 → 响 + 推一条 + 把手机页面切到它
const r = alarm.onIdle('s1', 125_000)
assert.equal(r.ringing, true)
assert.equal(alarm.status().ringing, true)
assert.equal(store.target, 's1', '响的时候应把目标会话切过去')
assert.equal(sent.length, 1)
assert.equal(sent[0].data.kind, 'alarm')
assert.equal(sent[0].tag, 'pb-alarm')
assert.equal(sent[0].requireInteraction, true)
assert.ok(Array.isArray(sent[0].vibrate) && sent[0].vibrate.length > 1, '要有长振动')
assert.ok(sent[0].title.includes('简历重写'), '标题里要有会话名')

// 5) 响了之后再掉一次 status 不该重复触发
assert.equal(alarm.onIdle('s1', 1000), false)
assert.equal(sent.length, 1)

// 6) 没人按停止 → 按间隔重复推，到上限就停
await sleep(2400)
assert.equal(sent.length, 3, `应该 1 条首推 + 2 条重复，实际 ${sent.length}`)
assert.equal(alarm.status().reps, 2)
assert.equal(alarm.status().ringing, true, '重复推完了仍然在响（页面还挂着）')

// 7) 停止 → 不再响 + 发一条静默推送把通知收掉 + 自动撤销指定
const stop = alarm.stop('test')
assert.equal(stop.ok, true)
assert.equal(alarm.status().ringing, false)
assert.equal(alarm.status().armed, false, '响铃是一次性的')
assert.equal(sent.at(-1).data.kind, 'alarm-off')
assert.equal(sent.at(-1).silent, true)
const n = sent.length
await sleep(1400)
assert.equal(sent.length, n, '停掉后不能再推')

// 8) 报错也响，但标题是「跑挂了」
alarm.arm('s3', '构建')
alarm.onError('s3', 'tool failed: exit 1')
assert.equal(alarm.status().ringing, true)
assert.equal(alarm.status().reason, 'error')
assert.ok(sent.at(-1).title.includes('跑挂了'))
assert.ok(sent.at(-1).body.includes('exit 1'))
alarm.stop('test')

// 9) 重启后还记着指定（但不会接着响）
alarm.arm('s4', '读论文')
const again = new Alarm({ ctx: {}, store, pusher, broadcast: () => {} })
assert.equal(again.status().armed, true)
assert.equal(again.status().sessionId, 's4')
assert.equal(again.status().ringing, false)

console.log('test-alarm: 全部通过')
