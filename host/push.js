/**
 * phone-bridge / push —— Web Push（VAPID + aes128gcm），走系统推送通道。
 *
 * 订阅只在本机生成：手机装到主屏后调 pushManager.subscribe()，拿到的
 * endpoint 存进 state.json。之后 sendNotification 把加密后的负载交给
 * Chrome 的推送服务，手机锁屏也能收到；负载只有手机那对密钥能解。
 */
import webpush from 'web-push'

export class Pusher {
  constructor(ctx, store) {
    this.ctx = ctx
    this.store = store
    this.lastError = null
    let keys = store.load('vapid.json', null)
    if (!keys?.publicKey || !keys?.privateKey) {
      keys = webpush.generateVAPIDKeys()
      store.save('vapid.json', keys)
      ctx.logger?.info?.('phone-bridge: 已生成 VAPID 密钥')
    }
    this.keys = keys
    try {
      webpush.setVapidDetails('mailto:phone-bridge@localhost', keys.publicKey, keys.privateKey)
    } catch (e) {
      this.lastError = String(e?.message || e)
      ctx.logger?.warn?.(`phone-bridge: VAPID 初始化失败 ${this.lastError}`)
    }
  }

  get publicKey() {
    return this.keys.publicKey
  }

  subscribed() {
    return this.store.devices().filter((d) => d.sub?.endpoint)
  }

  /**
   * 发一条通知。payload: { title, body, tag?, url?, actions?, data? }
   * 404/410 表示这个 endpoint 已经废了（卸载/清数据），顺手清掉。
   */
  async send(payload) {
    if (!this.store.config.pushEnabled) return { sent: 0, skipped: 'push disabled' }
    const targets = this.subscribed()
    if (!targets.length) return { sent: 0, skipped: 'no subscription' }
    const body = JSON.stringify(payload)
    let sent = 0
    for (const dev of targets) {
      try {
        await webpush.sendNotification(dev.sub, body, { TTL: 900, urgency: 'high' })
        sent += 1
      } catch (e) {
        const code = e?.statusCode
        this.lastError = `${code || ''} ${String(e?.body || e?.message || e).slice(0, 200)}`
        if (code === 404 || code === 410) {
          this.store.dropSub(dev.token)
          this.ctx.logger?.info?.('phone-bridge: 订阅已失效，已清除')
        } else {
          this.ctx.logger?.warn?.(`phone-bridge: 推送失败 ${this.lastError}`)
        }
      }
    }
    return { sent, failed: targets.length - sent, error: sent ? null : this.lastError }
  }
}
