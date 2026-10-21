# 手机桥 · phone-bridge

在手机上遥控这台电脑上的 DeepSeek Harness：下命令、批准工具调用、收消息推送。
全程走自己的 Tailscale 网络 + 浏览器原生 Web Push，不经过任何第三方中转。

## 装了什么

- 包：`@local/phone-bridge`（宿主插件，不带客户端 UI）
- Loader 行：`phone-bridge`
- 本机服务：`http://127.0.0.1:8791`（只绑回环，外面进不来）
- 手机地址：`https://<机器>.<tailnet>.ts.net/`（由 `tailscale serve` 转出来，HTTPS 是 Web Push 的前提）
- 数据：`~/.dsh/phone-bridge/{config,state,vapid}.json`（各自 600 权限）

## 三条通路

| 你要的 | 走的 DSH 接口 |
|---|---|
| 手机下命令 | `sessionController.prompt()`（和 GUI 输入框回车同一条路） |
| 手机批操作 | `approval/request` waterfall：手机与桌面 GUI **同时**收到，谁先答用谁 |
| 手机收推送 | `agent/status`（回完）、`agent/error`、`user-questions/request` → Web Push |
| 手机答题 | `user-questions/request` waterfall，选项直接渲染成按钮 |

审批用「竞速」而不是拦截：先让桌面照常弹框，再和手机赛跑。否则在电脑前反而只能掏手机。

## 首次接通

1. 电脑上打开 <http://127.0.0.1:8791/>（这个页面只有本机能开）。
2. 点「接通」把 Tailscale Serve 打开；tailnet 没开过 Serve/HTTPS 时会给出一次授权链接，点完再点一次「接通」。
3. 手机扫页面上的二维码（或手动打开那个 `https://…ts.net/`），输 6 位配对码。
4. 页面里点「开启」通知权限 → 浏览器菜单「添加到主屏幕」。
5. 回电脑页面点「发一条测试推送」，手机应该立刻弹一条。

## 配置（`~/.dsh/phone-bridge/config.json`）

| 键 | 默认 | 说明 |
|---|---|---|
| `port` | 8791 | 本机监听端口（改了要同步 `tailscale serve`） |
| `publicUrl` | 空 | 留空＝自动读 Tailscale 域名 |
| `approvalViaPhone` | true | 审批是否转发到手机 |
| `questionViaPhone` | true | `ask_user_question` 是否转发 |
| `pushTurnEnd` | true | 每轮回完推一条（正文是回复末尾片段） |
| `pushErrors` | true | 出错推送 |
| `snippetChars` | 220 | 推送里带多少字 |
| `minTurnMs` | 4000 | 短于这个时长的回合不推 |

## 自测

```sh
node scripts/test-race.mjs     # 审批竞速 / 失效 / 提问（不需要 harness）
python3 scripts/make-icons.py  # 重新生成 PWA 图标
```

## 已知边界

- 电脑睡眠或 App 没开时，命令和推送都断（推送本身不排队）。
- 手机上批准的是「这一次」，不会改沙箱/权限策略。
- 微信里打不开：PWA 必须用系统浏览器。
