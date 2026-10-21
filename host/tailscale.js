/**
 * phone-bridge / tailscale —— 读 tailnet 域名、检查 / 拉起 Tailscale Serve。
 *
 * 注意：macOS 的 Tailscale CLI 就是 GUI 那个可执行文件，在一个**过于干净的环境**里
 * 调用它会去找 GUI 并报：
 *   The Tailscale GUI failed to start: ... (Tailscale.CLIError error 3.)
 * 实测 env -i 能稳定复现，环境里只要有 TERM / SHLVL 之类任意一个变量就正常。
 * 所以这里两件事都做：
 *   1. 给子进程补一份最小可用环境（TERM/HOME/PATH/SHLVL…）；
 *   2. 拿不到 JSON 也绝不抛错、绝不谎报"没登录"——只把原始输出记下来给页面看。
 * 权威地址永远以 config.json 的 publicUrl 为准，CLI 只是锦上添花。
 */
import { execFile } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

/**
 * 各平台的 Tailscale CLI 位置：
 *   macOS    /Applications/Tailscale.app/Contents/MacOS/Tailscale（就是 GUI 那个二进制）
 *   Windows  C:\Program Files\Tailscale\tailscale.exe
 *   Linux    PATH 里的 tailscale
 * 可用 DSH_TAILSCALE_BIN 覆盖。
 */
function findBin() {
  if (process.env.DSH_TAILSCALE_BIN) return process.env.DSH_TAILSCALE_BIN
  const candidates = []
  if (process.platform === 'darwin') {
    candidates.push('/Applications/Tailscale.app/Contents/MacOS/Tailscale')
  } else if (process.platform === 'win32') {
    for (const base of [process.env.ProgramFiles, process.env['ProgramFiles(x86)'], 'C:\\Program Files']) {
      if (base) candidates.push(path.join(base, 'Tailscale', 'tailscale.exe'))
    }
    if (process.env.LOCALAPPDATA) candidates.push(path.join(process.env.LOCALAPPDATA, 'Tailscale', 'tailscale.exe'))
  } else {
    candidates.push('/usr/bin/tailscale', '/usr/local/bin/tailscale')
  }
  for (const p of candidates) {
    try {
      if (fs.existsSync(p)) return p
    } catch {}
  }
  return process.platform === 'win32' ? 'tailscale.exe' : 'tailscale'
}

const BIN = findBin()

function childEnv() {
  // Windows：原样继承（CLI 不依赖 TERM/SHLVL 这种“终端感”变量）。
  if (process.platform === 'win32') return { ...process.env }
  // macOS/Linux：App 宿主环境太空，CLI 会去找 GUI 并报
  // "The Tailscale GUI failed to start"，所以补一份最小环境。
  const home = process.env.HOME || os.homedir()
  const user = process.env.USER || os.userInfo().username
  return {
    ...process.env,
    HOME: home,
    PATH: process.env.PATH || '/usr/bin:/bin:/usr/sbin:/sbin',
    TERM: process.env.TERM || 'xterm-256color',
    SHLVL: process.env.SHLVL || '1',
    USER: user,
    LOGNAME: process.env.LOGNAME || user,
  }
}

function run(args, timeout = 5000) {
  return new Promise((resolve) => {
    execFile(BIN, args, { timeout, env: childEnv(), maxBuffer: 4 << 20 }, (err, stdout, stderr) => {
      resolve({ err, out: String(stdout || ''), errOut: String(stderr || '') })
    })
  })
}

const GUI_ERR = /GUI failed to start|Tailscale\.CLIError/i

let cache = null
let cachedAt = 0

export async function tailscaleInfo(force = false) {
  if (!force && cache && Date.now() - cachedAt < 60_000) return cache
  const res = { dnsName: '', url: '', serve: false, serveKnown: false, detail: '', raw: '', error: null }

  try {
    const st = await run(['status', '--json'])
    const text = st.out.trim()
    if (text.startsWith('{')) {
      try {
        const d = JSON.parse(text)
        const dns = String(d?.Self?.DNSName || '').replace(/\.$/, '')
        res.dnsName = dns
        if (dns) res.url = `https://${dns}/`
      } catch {
        res.raw = text.slice(0, 300)
        res.error = '状态输出不是合法 JSON'
      }
    } else {
      res.raw = (text || st.errOut || '').slice(0, 300)
      res.error = GUI_ERR.test(res.raw) ? 'cli-unavailable' : 'cli-empty'
    }

    const sv = await run(['serve', 'status'])
    const s = (sv.out || sv.errOut || '').trim()
    res.detail = s.slice(0, 400)
    if (GUI_ERR.test(s)) {
      res.serveKnown = false
    } else {
      res.serveKnown = true
      res.serve = /proxy http|tailnet only|https:\/\//i.test(s)
      // 备用：即使 status --json 拿不到，serve 的输出里也带着完整域名
      if (!res.url) {
        const m = s.match(/https:\/\/[a-z0-9][a-z0-9.-]*\.ts\.net/i)
        if (m) res.url = m[0] + '/'
      }
    }
  } catch (e) {
    res.error = String(e?.message || e)
  }

  cache = res
  cachedAt = Date.now()
  return res
}

/** 尝试 `tailscale serve --bg <port>`；失败时把原文交回页面，由页面给出手动命令。 */
export async function enableServe(port) {
  const r = await run(['serve', '--bg', String(port)], 12_000)
  const text = (r.out + r.errOut).trim()
  const link = (text.match(/https:\/\/login\.tailscale\.com\/\S+/) || [''])[0]
  const ok = /proxy http|Available within your tailnet|Already serving|started and running/i.test(text)
  await tailscaleInfo(true)
  return { ok, link, text: text.slice(0, 600), cliUnavailable: GUI_ERR.test(text) }
}
