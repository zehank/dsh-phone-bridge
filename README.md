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

## Windows

同一套代码，CLI 位置和环境修正按平台走：

| | macOS | Windows |
|---|---|---|
| Tailscale CLI | `/Applications/Tailscale.app/Contents/MacOS/Tailscale` | `C:\Program Files\Tailscale\tailscale.exe` |
| 服务地址 | `http://127.0.0.1:8791` | `http://127.0.0.1:8791` |
| 手机地址 | `https://<机器名>.<tailnet>.ts.net/` | 同左（机器名换成这台 PC） |

在 Windows 上装：

```powershell
git clone https://github.com/zehank/dsh-phone-bridge "$HOME\dsh-phone-bridge"
cd "$HOME\dsh-phone-bridge"
npm install                      # 或者 pnpm install
dsh plugin --profile desktop add "$HOME\dsh-phone-bridge"
tailscale serve --bg 8791        # 只要这台 PC 还没开过；tailnet 已开过 HTTPS 就不用再授权
```

装完**重启一次 DeepSeek Harness**，再打开 <http://127.0.0.1:8791/> 走同样的配对流程。
两台电脑各自配对一次，手机上会出现两个图标（名字是 `手机桥 · <机器名>`，例如 `手机桥 · rzhank`），互不干扰。

Windows 上要注意的：
- `tailscale.exe` 在 PATH 里时不用管路径；找不到就把完整路径塞进环境变量 `DSH_TAILSCALE_BIN`。
- 只监听 127.0.0.1，Windows 防火墙不会弹窗。
- 睡眠/休眠同样会断，`powercfg /change standby-timeout-ac 0` 可临时关掉。

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
