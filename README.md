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

审批**不延迟**：手机和桌面同一刻各自收到，谁先答用谁的（`desktopDelayMs` 默认 0）。
把 `desktopDelayMs` 配成 >0 才回到「先只给手机一个窗口、手机没答再弹桌面」的老策略——
代价是桌面框会晚几秒，好处是手机答了桌面根本不用弹（但桌面框被手机赢了不会自动消失，用 0 时这个坑也就不存在了）。

## 手机端能做什么

| 顶栏 | 作用 | 走的宿主接口 |
|---|---|---|
| 模式 · xxx | 切 agent preset（standard / ptc / minimal / cordis …） | `agentPresets.remoteExportList()` / `select()` |
| 模型 · xxx | 切当前会话的模型 | `sessionController.modelCatalog()` / `selectModel()` |
| 权限 · xxx | 切沙箱 + 审批策略（read-only / workspace-write / danger-full-access …） | `permissionPresets.catalog()` / `current()` / `set()` |
| ＋文件 | 选文件上传，发送时一起进 prompt | `fileUploads.uploadStream()` + `bindPrompt()` |
| 工作区 · … | 切当前会话的工作目录 | `workspaceRegistry.list()` / `workingDirectory.set()` |
| ＋会话 | 在工作区里新建会话 | `sessionController.create()` |
| 定时 | 看 / 加 / 删定时任务 | `schedule.catalog()` / `create()` / `delete()` |
| 🔔 跑完叫我 | 指定会话，跑完在这台手机上响铃 | `agent/status` 掉回空闲 → `alarm.js` |

顶栏一行不够就左右滑。

页面自带版本号（`BUILD`），发现和宿主版本不一致会自动重载一次，避免 PWA 一直跑旧 JS。

审批全过程写进 `~/.dsh/phone-bridge/approvals.json`（谁先答、时间、结果），电脑管理页「最近审批」能直接看。

消息流只显示你说的话和我的回答。DSH 每轮注入的 `time-context` / `runtime-context` / `dsh-mnemon`
（`user/message` 里 `source.kind !== 'user'` 的那些）不会当消息出现，而是折进回答上方的
「思考过程」按钮，点开才看得到。下面那行「用量」收起时只显示轮/步/token 摘要，点开是浮层：输入输出、
缓存命中率、上下文占用、账户余额；点浮层外面自动关。

文件走 `POST /api/upload`（原始字节，25MB 上限），拿到 `receiptId` 后随 `/api/say` 一起提交。

## 响铃「跑完叫我」

不知道任务要跑多久，所以不是定时器：在手机上选中会话，点一下 **🔔 跑完叫我**，
宿主盯着这个会话的 `agent/status`，等它从 `running` 掉回空闲（或 `agent/error`）那一刻才响。
响一次就撤销，不用记得去关。

- **页面开着**：循环双音铃声（WebAudio，不用音频文件）+ 长振动 + 屏幕常亮，全屏红色「停止响铃」。
  浏览器只允许用户点过页面之后出声，所以点「响铃」这个动作本身就把音频解锁了；
  万一还是被拦，页面会提示「点一下屏幕才会出声」。
- **页面关着/锁屏**：Web Push。推送本身只能叮一下，所以按 `alarmRepeatMs` 反复推、
  每次 `renotify` 重新出声振动，通知常驻并带「停止响铃」按钮，点通知进来就是全屏响铃页。
- 停止：页面按钮 / 通知按钮 → `POST /api/alarm/stop` → 宿主停推，并补一条静默推送让通知自己收掉。

接口：`POST /api/alarm/{arm,disarm,test,stop}`（arm 传 `{sessionId}`，不传就用手机当前选中的会话）。

## 安卓 App（一台手机遥控多台电脑）

`android/` 是**纯原生 UI**（没有 WebView，也不加载网页）：设备列表 → 会话 → 原生聊天界面，
外加后台长连接和真闹钟。通知不走浏览器推送，全由 App 自己的前台服务产生。

```sh
cd android && ./build.sh          # 出 phonebridge.apk（40KB，已签名）
cp android/phonebridge.apk web/   # 宿主就会在 https://<host>/app.apk 发出来
```

- 不需要 Gradle / Android Studio：只用 JDK 17 + build-tools 34 + platform 34，
  工具链在 `.dsh/android/`（`PB_TOOLCHAIN` 可覆盖）。
- 手机装：手机页面 设置 → **安卓 App**，或直接开 `https://<host>/app.apk`。
- 加设备：**扫电脑管理页上那张二维码**（里面是 `地址#pair=配对码`，扫完自动配对），
  或者手填 Tailscale 地址 + 6 位配对码；一台手机可以加多台，各自独立连接与重连。
- 扫码：`QrScanActivity`（Camera1 预览 + ZXing core 解 NV21 的 Y 平面），
  依赖 `libs/zxing-core.jar`（593KB，纯 Java，无 Android 依赖，build.sh 里一并递给 javac/d8）。
  相机起不来或没授权时给「手动输入」兜底。
- 主界面：每台一行，显示在线 / 会话数 / 跑着几个；点进去是原生聊天页：
  气泡消息、输入框发送、中断、待批准卡片（允许一次 / 拒绝）、提问选项、🔔 跑完叫我、点标题切会话。
  数据靠 `/api/state` 2 秒轮询（只在前台轮），动作直接打 bridge 接口。
- 原生还没搬过来的：模型 / 权限 / 模式切换、文件上传、定时任务、思考过程折叠（手机页里仍有）。
- 后台：`LinkService` 前台服务，每台一条 SSE，断了 5 秒后自己重连；一台掉线不影响其他台。
- 通知：待批准（带「允许一次 / 拒绝」按钮）、会话跑完（跑了 >30 秒才报）、响铃。
- 响铃：`AlarmCenter` 拿系统闹钟铃声在 `USAGE_ALARM` 上循环 + 长振动 + 全屏 `AlarmActivity`，
  按「停止响铃」才停（也走 `POST /api/alarm/stop`）。

已知边界：WebView 里没有 Web Push（所以 App 必须常驻前台服务）；选择文件的系统面板还没接（v1）；
targetSdk 33（为了 `USE_FULL_SCREEN_INTENT` 默认可用），Android 14+ 也能装。

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

## 会话列表与历史

点顶部会话标题能看到**所有会话**：正在跑的（`agents.list()`）+ 以前跑过的（`sessionController.list()`，
没标题就退回工作目录名）。切到旧会话时会用 `sessionController.inspect()` 回放最近 120 条消息
（注入块同样折进「思考过程」），历史缓存 30 秒。

## 接口一览

| 方法 | 路由 | 用途 |
|---|---|---|
| POST | `/api/cwd` | 设工作目录（`workingDirectory.set`） |
| GET | `/api/workspaces` | 工作区列表 + 当前目录 |
| POST | `/api/session` | 新建会话（可带 `cwd` / `workspaceId`） |
| GET | `/api/schedules` | 定时任务列表 |
| POST | `/api/schedule` | 新建（`daily:{time}` 或 `everySeconds`） |
| POST | `/api/schedule/delete` | 删除 |
| GET | `/api/stats?full=1` | 用量 + 余额（余额缓存 60s） |

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
| `alarmRepeatMs` | 20000 | 响铃后每隔多久再推一次（最小 1000） |
| `alarmMaxReps` | 6 | 最多再推几次（0＝只推一次） |
| `alarmPushes` | true | 响铃走不走推送（关＝只靠页面开着时本地响） |

## 自测

```sh
node scripts/test-race.mjs     # 审批竞速 / 失效 / 提问（不需要 harness）
node scripts/test-alarm.mjs    # 「跑完叫我」：指定 / 重复推 / 停止 / 报错 / 重启恢复
python3 scripts/make-icons.py  # 重新生成 PWA 图标
```

## 已知边界

- 电脑睡眠或 App 没开时，命令和推送都断（推送本身不排队）。
- 手机上批准的是「这一次」，不会改沙箱/权限策略。
- 微信里打不开：PWA 必须用系统浏览器。
- 网页做不出「闹钟」那种一直响：浏览器不许后台循环播音频，页面一关就只剩推送的叮 + 振动。
  要更像闹钟就把手机桥页面留在后台别杀，或者把 `alarmRepeatMs` 调小（比如 12000）让推送更密。
