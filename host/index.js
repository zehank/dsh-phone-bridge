/**
 * phone-bridge —— 手机遥控 DeepSeek Harness。
 *
 * 手机（PWA，装在主屏）经 Tailscale 的 HTTPS 域名连到本机 127.0.0.1 上的这个服务：
 *   · 下命令 → sessionController.prompt()
 *   · 批操作 → approval/request 双通道竞速（手机 vs 桌面 GUI）
 *   · 弹消息 → 系统 Web Push（锁屏也能收到）
 *
 * 所有能力都走宿主已有的公开 API/事件，不 import @deepseek-ai/*。
 */
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { Store } from './store.js'
import { Pusher } from './push.js'
import { Bridge } from './bridge.js'
import { createServer } from './server.js'
import { tailscaleInfo } from './tailscale.js'

export const name = 'phone-bridge'

const webRoot = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'web')

export function apply(ctx) {
  const store = new Store()
  if (store.config.enabled === false) {
    ctx.logger?.info?.('phone-bridge: 配置里 enabled=false，跳过')
    return
  }

  const pusher = new Pusher(ctx, store)
  const bridge = new Bridge(ctx, store, pusher)
  const server = createServer({ ctx, store, bridge, pusher, webRoot })

  bridge.wire()

  ctx.effect(() => {
    server.start()
    return () => server.stop()
  }, 'phone-bridge http server')

  ctx.effect(() => () => bridge.dispose(), 'phone-bridge bridge')

  const cfg = store.config
  ctx.logger?.info?.(
    `phone-bridge: 已启动 ${server.localUrl()} · 推送${cfg.pushEnabled ? '开' : '关'} · 审批转发${cfg.approvalViaPhone ? '开' : '关'}`,
  )
  tailscaleInfo()
    .then((ts) => {
      if (ts.serve && ts.url) ctx.logger?.info?.(`phone-bridge: 手机地址 ${ts.url}`)
      else
        ctx.logger?.warn?.(
          `phone-bridge: Tailscale 还没接通（${ts.error || (ts.dnsName ? 'Serve 未开启' : '未登录')}），打开 ${server.localUrl()} 点「接通」`,
        )
    })
    .catch(() => {})
}
