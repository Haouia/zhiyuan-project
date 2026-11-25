#!/usr/bin/env node
/**
 * ui_capture.mjs — dependency-free Chrome DevTools Protocol (CDP) harness for the
 * 知源 admin / Ask SPA. Node 24 ESM, uses global fetch + WebSocket only.
 *
 * Usage:
 *   node scripts/ui_capture.mjs                 # baseline pass against the OLD UI (before evidence)
 *   node scripts/ui_capture.mjs --phase=new     # full new-UI E2E visual regression (run after deploy)
 *   node scripts/ui_capture.mjs --phase=admin   # new 管理 pages (RAGAS 评测 / 系统监控 / 用户与权限) after deploy
 *   node scripts/ui_capture.mjs --phase=register # 登录页主题 + 注册/邀请码/擢升浏览器 E2E (task-13)
 *   node scripts/ui_capture.mjs --phase=chat    # /ask/ 连续聊天：hero→固定 dock、追问携带 history、Enter/Shift+Enter、390 (task-15)
 *   node scripts/ui_capture.mjs --port=9444     # force a debugging port
 *   node scripts/ui_capture.mjs --keep-profile  # keep the temp Chrome profile for debugging
 *
 * Writes: artifacts/screenshots/*.png, artifacts/ui_visual_report.json (+ -new.json / -admin.json /
 *         -register.json / -chat.json for the new / admin / register / chat phases), artifacts/chrome-stderr-<phase>.log,
 *         artifacts/failure-<phase>.* on fatal errors.
 * Exit codes: 0 evidence captured (baseline always); 1 fatal harness error or failing non-baseline checks.
 */
import { spawn, spawnSync } from 'node:child_process'
import { mkdir, writeFile, rm, readFile } from 'node:fs/promises'
import { openSync, closeSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import net from 'node:net'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const ARTIFACTS = path.join(ROOT, 'artifacts')
const SHOTS = path.join(ARTIFACTS, 'screenshots')
const CHROME = process.env.UI_CHROME_PATH
  || 'C:\\Users\\legion\\AppData\\Local\\ms-playwright\\chromium-1248\\chrome-win64\\chrome.exe'
const APP_ORIGIN = 'http://127.0.0.1:8080'
const ADMIN_URL = APP_ORIGIN + '/admin/'
const ASK_URL = APP_ORIGIN + '/ask/'
const KEYCLOAK_ORIGIN = 'http://127.0.0.1:8180'
const CREDS = {
  username: process.env.UI_ADMIN_USER || 'a-admin',
  password: process.env.UI_ADMIN_PASSWORD || 'RagLocalExample42!',
}
const LOGIN_TEXTS = ['使用单点登录', '单点登录', '统一身份登录', '企业登录', 'SSO 登录', '立即登录', '登录']
const VIEWPORTS = {
  desktop: { name: 'desktop-1440x900', width: 1440, height: 900, deviceScaleFactor: 1, mobile: false },
  mid: { name: 'mid-1024x768', width: 1024, height: 768, deviceScaleFactor: 1, mobile: false },
  mobile: { name: 'mobile-390x844', width: 390, height: 844, deviceScaleFactor: 1, mobile: true },
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/* ------------------------------------------------------------------ args */
function parseArgs(argv) {
  const out = { phase: 'baseline', port: null, keepProfile: false, help: false }
  for (const raw of argv) {
    const match = /^--([^=]+)(?:=(.*))?$/.exec(raw)
    if (!match) continue
    const [, key, value] = match
    if (key === 'phase') out.phase = value || 'baseline'
    else if (key === 'port') out.port = value ? Number(value) : null
    else if (key === 'keep-profile') out.keepProfile = true
    else if (key === 'help') out.help = true
  }
  if (!['baseline', 'new', 'admin', 'register', 'chat'].includes(out.phase)) throw new Error('--phase must be "baseline", "new", "admin", "register" or "chat"')
  return out
}

/* ------------------------------------------------------------------ report */
function createReport(phase, args) {
  return {
    schemaVersion: 1,
    phase,
    note: phase === 'baseline'
      ? '基线（旧绿色主题 UI）证据，仅作 before 对照，不代表最终设计目标。'
      : (phase === 'admin'
        ? '新管理页面（RAGAS 评测 / 系统监控 / 用户与权限）端到端回归；checks 中每项 pass/fail。'
        : (phase === 'register'
          ? '登录页主题 + 注册 / 邀请码 / 用户权限操作端到端回归（task-13）；checks 中每项 pass/fail。'
          : (phase === 'chat'
            ? '/ask/ 连续聊天端到端回归：hero 输入 → 固定底部 dock、追问携带 history、Enter/Shift+Enter、开始新对话、390 适配（task-15）。'
            : '新 UI 端到端视觉回归；checks 中每项 pass/fail。'))),
    startedAt: new Date().toISOString(),
    baseUrl: APP_ORIGIN,
    chromePath: CHROME,
    args,
    login: null,
    steps: [],
    checks: [],
    screenshots: [],
    consoleErrors: [],
    failedRequests: [],
    ignoredRequests: [],
    overflows: [],
    pageStats: {},
    deployedAssets: [],
    fatal: null,
    summary: null,
  }
}

function step(report, name, status, detail) {
  report.steps.push({ name, status, detail: detail ?? '', at: new Date().toISOString() })
  const mark = { pass: 'PASS', fail: 'FAIL', warn: 'WARN', info: 'INFO' }[status] || status
  console.log(`[${mark}] ${name}${detail ? ' — ' + detail : ''}`)
}
function pass(report, name, detail) { step(report, name, 'pass', detail); return true }
function fail(report, name, detail) { step(report, name, 'fail', detail); return false }
function info(report, name, detail) { step(report, name, 'info', detail); return true }
function warn(report, name, detail) { step(report, name, 'warn', detail); return true }

/* ------------------------------------------------------------------ CDP core */
class CDP {
  constructor(wsUrl) {
    this.wsUrl = wsUrl
    this.ws = null
    this.nextId = 1
    this.pending = new Map()
    this.listeners = new Map()
    this.closed = false
    this.closeReason = null
  }

  async connect() {
    const ws = new WebSocket(this.wsUrl)
    this.ws = ws
    ws.addEventListener('message', (event) => this.#onMessage(event))
    ws.addEventListener('close', () => {
      this.closed = true
      for (const [, pending] of this.pending) {
        clearTimeout(pending.timer)
        pending.reject(new Error(`CDP socket closed during ${pending.method}`))
      }
      this.pending.clear()
    })
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP WebSocket connect timeout (15s)')), 15000)
      timer.unref?.()
      ws.addEventListener('open', () => { clearTimeout(timer); resolve() }, { once: true })
      ws.addEventListener('error', (event) => {
        clearTimeout(timer)
        reject(new Error('CDP WebSocket error: ' + (event?.message || 'unknown')))
      }, { once: true })
    })
  }

  #onMessage(event) {
    let raw
    if (typeof event.data === 'string') raw = event.data
    else if (event.data instanceof ArrayBuffer) raw = Buffer.from(event.data).toString('utf8')
    else raw = String(event.data)
    let message
    try { message = JSON.parse(raw) } catch { return }
    if (message.id != null) {
      const pending = this.pending.get(message.id)
      if (!pending) return
      this.pending.delete(message.id)
      clearTimeout(pending.timer)
      if (message.error) {
        const code = message.error.code != null ? ` (code ${message.error.code})` : ''
        pending.reject(new Error(`${pending.method} failed: ${message.error.message}${code}`))
      } else {
        pending.resolve(message.result)
      }
      return
    }
    const handlers = this.listeners.get(message.method)
    if (!handlers) return
    for (const handler of [...handlers]) {
      try { handler(message.params || {}, message.sessionId) } catch (error) { console.error('[cdp listener]', message.method, error.message) }
    }
  }

  send(method, params = {}, { sessionId = null, timeout = 30000 } = {}) {
    if (this.closed) return Promise.reject(new Error(`CDP closed, cannot send ${method}`))
    const id = this.nextId++
    const payload = { id, method, params }
    if (sessionId) payload.sessionId = sessionId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`${method} timeout after ${timeout}ms`))
      }, timeout)
      timer.unref?.()
      this.pending.set(id, { resolve, reject, timer, method })
      try { this.ws.send(JSON.stringify(payload)) } catch (error) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(error)
      }
    })
  }

  on(method, fn) {
    if (!this.listeners.has(method)) this.listeners.set(method, new Set())
    this.listeners.get(method).add(fn)
  }

  async close() {
    if (this.closed) return
    try {
      await Promise.race([this.send('Browser.close', {}, { timeout: 2500 }), sleep(2500)])
    } catch { /* socket usually dies first */ }
    try { this.ws?.close() } catch { /* ignore */ }
  }
}

class PageSession {
  constructor(cdp, sessionId, ctx) {
    this.cdp = cdp
    this.sessionId = sessionId
    this.ctx = ctx
    this.boundHandlers = []
  }

  send(method, params = {}, opts = {}) {
    return this.cdp.send(method, params, { ...opts, sessionId: this.sessionId })
  }

  on(method, fn) {
    const wrapped = (params, sessionId) => {
      if (sessionId && sessionId !== this.sessionId) return
      fn(params)
    }
    this.cdp.on(method, wrapped)
    this.boundHandlers.push([method, wrapped])
  }

  async enable() {
    await this.send('Page.enable')
    await this.send('Runtime.enable')
    await this.send('Log.enable')
    await this.send('Network.enable')
    await this.send('DOM.enable')
  }

  /* ---------- evaluate ---------- */
  async eval(expression, { timeout = 30000, awaitPromise = true } = {}) {
    const result = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise, userGesture: true,
    }, { timeout })
    if (result.exceptionDetails) {
      const details = result.exceptionDetails
      const description = details.exception?.description || details.text || 'evaluate failed'
      throw new Error(description.split('\n')[0])
    }
    return result.result?.value
  }

  /** Evaluate a function with JSON args, serialized through toString() (no closure access). */
  evalFn(fn, args = [], opts = {}) {
    const source = `(${fn.toString()})(${args.map((arg) => JSON.stringify(arg)).join(',')})`
    return this.eval(source, opts)
  }

  async safeEval(expression, opts = {}) {
    try { return await this.eval(expression, opts) } catch { return null }
  }

  async safeEvalFn(fn, args = [], opts = {}) {
    try { return await this.evalFn(fn, args, opts) } catch { return null }
  }

  async waitForFn(fn, args = [], label = 'condition', { timeout = 20000, interval = 120 } = {}) {
    const started = Date.now()
    let lastError = null
    let lastValue = undefined
    while (Date.now() - started < timeout) {
      try {
        const value = await this.evalFn(fn, args)
        if (value) return value
        lastValue = value
      } catch (error) { lastError = error }
      await sleep(interval)
    }
    const tail = lastError ? `; lastError=${lastError.message}` : (lastValue !== undefined ? `; last=${JSON.stringify(lastValue)}` : '')
    throw new Error(`等待超时(${timeout}ms): ${label}${tail}`)
  }

  /* ---------- navigation / viewport ---------- */
  async navigate(url) {
    await this.send('Page.navigate', { url }, { timeout: 20000 })
    await this.waitForFn(() => document.readyState === 'complete', [], 'document.readyState=complete', { timeout: 30000 })
  }

  async reload() {
    await this.send('Page.reload', { ignoreCache: false }, { timeout: 20000 })
    await this.waitForFn(() => document.readyState === 'complete', [], 'document.readyState=complete', { timeout: 30000 })
  }

  async setViewport(viewport) {
    this.ctx.viewport = viewport.name
    await this.send('Emulation.setDeviceMetricsOverride', {
      width: viewport.width,
      height: viewport.height,
      screenWidth: viewport.width,
      screenHeight: viewport.height,
      deviceScaleFactor: viewport.deviceScaleFactor ?? 1,
      mobile: !!viewport.mobile,
    })
    if (viewport.mobile) {
      await this.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 })
      // Switching to a mobile viewport on an already-loaded page can leave Chrome with a stale
      // page scale (innerWidth != requested width), which then fabricates horizontal overflow.
      // Force scale 1 so the 390px measurement is honest.
      await this.send('Emulation.setPageScaleFactor', { pageScaleFactor: 1 }).catch(() => {})
    } else {
      await this.send('Emulation.setTouchEmulationEnabled', { enabled: false }).catch(() => {})
    }
  }

  async settle(ms = 350) {
    await this.evalFn(async () => {
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
      return true
    }).catch(() => {})
    await sleep(ms)
  }

  async bodyText() {
    return (await this.safeEval('document.body ? (document.body.innerText || "") : ""')) || ''
  }

  async location() {
    return (await this.safeEval('location.href')) || ''
  }

  async clickByText(texts, { timeout = 15000 } = {}) {
    const wanted = Array.isArray(texts) ? texts : [texts]
    const started = Date.now()
    let available = []
    while (Date.now() - started < timeout) {
      try {
        const result = await this.evalFn((candidates) => {
          const norm = (value) => (value || '').replace(/\s+/g, ' ').trim()
          const nodes = [...document.querySelectorAll('button,a,[role="button"],input[type="submit"],[role="tab"]')]
          const visible = nodes.filter((node) => {
            const rect = node.getBoundingClientRect()
            const style = getComputedStyle(node)
            return rect.width > 2 && rect.height > 2 && style.visibility !== 'hidden' && style.display !== 'none' && Number(style.opacity || 1) > 0.05
          })
          for (const text of candidates) {
            const exact = visible.find((node) => norm(node.innerText || node.value) === text)
            const partial = visible.find((node) => norm(node.innerText || node.value).includes(text))
            const hit = exact || partial
            if (hit) {
              hit.scrollIntoView({ block: 'center', inline: 'nearest' })
              hit.click()
              return { clicked: true, text: norm(hit.innerText || hit.value), tag: hit.tagName.toLowerCase() }
            }
          }
          return { clicked: false, available: visible.map((node) => norm(node.innerText || node.value)).filter(Boolean).slice(0, 30) }
        }, [wanted])
        if (result?.clicked) return result
        available = result?.available || []
      } catch { /* navigation in progress */ }
      await sleep(150)
    }
    throw new Error(`未找到可点击文本 ${JSON.stringify(wanted)}；当前可见候选: ${JSON.stringify(available)}`)
  }

  async clickSelector(selector, { timeout = 15000 } = {}) {
    await this.waitForFn((sel) => !!document.querySelector(sel), [selector], `元素 ${selector} 出现`, { timeout })
    return this.evalFn((sel) => {
      const element = document.querySelector(sel)
      if (!element) return false
      element.scrollIntoView({ block: 'center', inline: 'nearest' })
      element.click()
      return true
    }, [selector])
  }

  async fill(selector, value, { timeout = 15000 } = {}) {
    await this.waitForFn((sel) => !!document.querySelector(sel), [selector], `输入框 ${selector} 出现`, { timeout })
    const result = await this.evalFn((sel, val) => {
      const element = document.querySelector(sel)
      if (!element) return { ok: false, reason: 'not found' }
      element.focus()
      const proto = element instanceof HTMLTextAreaElement
        ? HTMLTextAreaElement.prototype
        : (element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype)
      const descriptor = Object.getOwnPropertyDescriptor(proto, 'value')
      if (descriptor && descriptor.set) descriptor.set.call(element, val)
      else element.value = val
      element.dispatchEvent(new Event('input', { bubbles: true }))
      element.dispatchEvent(new Event('change', { bubbles: true }))
      return { ok: true, value: element.value, tag: element.tagName.toLowerCase() }
    }, [selector, value])
    if (!result?.ok) throw new Error(`填写 ${selector} 失败: ${JSON.stringify(result)}`)
    return result
  }

  async pressTab() {
    await this.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9 })
    await this.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, nativeVirtualKeyCode: 9 })
    await sleep(120)
  }

  async pressEscape() {
    await this.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 })
    await this.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 })
    await sleep(250)
  }

  async screenshot(name, { fullPage = false } = {}) {
    const result = await this.send('Page.captureScreenshot', {
      format: 'png',
      fromSurface: true,
      captureBeyondViewport: fullPage,
    }, { timeout: 60000 })
    const file = `${name}.png`
    const absolute = path.join(SHOTS, file)
    const buffer = Buffer.from(result.data, 'base64')
    await writeFile(absolute, buffer)
    const entry = {
      name,
      file: 'artifacts/screenshots/' + file,
      viewport: this.ctx.viewport,
      activity: this.ctx.activity,
      bytes: buffer.length,
      at: new Date().toISOString(),
    }
    this.ctx.report.screenshots.push(entry)
    console.log(`[shot] ${file} (${Math.round(buffer.length / 1024)} KB, ${this.ctx.viewport})`)
    return entry
  }

  /** Horizontal overflow probe (documentElement.scrollWidth vs clientWidth) + offending elements. */
  async overflowCheck(label) {
    const result = await this.evalFn((labelText) => {
      const documentElement = document.documentElement
      const viewportWidth = window.innerWidth
      // On mobile Chrome the layout viewport (innerWidth) can exceed the document client
      // width; use the tighter of both as the "content should not exceed this" reference.
      const refWidth = Math.min(viewportWidth, documentElement.clientWidth) || viewportWidth
      const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
      const classNameOf = (element) => {
        const raw = typeof element.className === 'string' ? element.className : (element.className?.baseVal || '')
        return norm(raw).slice(0, 100)
      }
      const offenders = []
      const scrollContainers = []
      const chains = []
      const seen = new Set()
      let maxRight = null
      const chainOf = (element, index) => {
        const chain = []
        let cursor = element.parentElement
        for (let depth = 0; cursor && depth < 8; depth += 1) {
          const rect = cursor.getBoundingClientRect()
          const style = getComputedStyle(cursor)
          chain.push({
            tag: cursor.tagName.toLowerCase(),
            cls: (typeof cursor.className === 'string' ? cursor.className : '').slice(0, 70),
            width: Math.round(rect.width),
            right: Math.round(rect.right),
            scrollWidth: cursor.scrollWidth,
            clientWidth: cursor.clientWidth,
            overflowX: style.overflowX,
          })
          cursor = cursor.parentElement
        }
        chains.push({ index, chain })
      }
      for (const element of document.querySelectorAll('body *')) {
        const rect = element.getBoundingClientRect()
        if (rect.width <= 0 || rect.height <= 0) continue
        const style = getComputedStyle(element)
        if (style.visibility === 'hidden' || style.display === 'none') continue
        const entry = {
          tag: element.tagName.toLowerCase(),
          cls: classNameOf(element),
          left: Math.round(rect.left),
          right: Math.round(rect.right),
          width: Math.round(rect.width),
          position: style.position,
          overflowX: style.overflowX,
          text: norm(element.innerText).slice(0, 50),
        }
        if (!maxRight || entry.right > maxRight.right) maxRight = entry
        const key = entry.tag + '|' + entry.cls + '|' + entry.right
        if (rect.right > refWidth + 1 && !seen.has(key) && offenders.length < 10) {
          seen.add(key)
          if (offenders.length < 2) chainOf(element, offenders.length)
          offenders.push(entry)
        }
        if (element.clientWidth > 0 && element.scrollWidth > element.clientWidth + 1 && scrollContainers.length < 10) {
          scrollContainers.push({
            tag: entry.tag,
            cls: entry.cls,
            scrollWidth: element.scrollWidth,
            clientWidth: element.clientWidth,
            overflowX: style.overflowX,
            width: entry.width,
          })
        }
      }
      // Second pass: elements that overflow to the right but were filtered out above
      // (zero-size wrappers, hidden/closed overlays) — often the real scrollWidth cause.
      const hiddenOverflow = []
      for (const element of document.querySelectorAll('body *')) {
        const rect = element.getBoundingClientRect()
        if (rect.right <= refWidth + 1) continue
        const style = getComputedStyle(element)
        if (rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none') continue
        hiddenOverflow.push({
          tag: element.tagName.toLowerCase(),
          cls: classNameOf(element),
          left: Math.round(rect.left),
          right: Math.round(rect.right),
          width: Math.round(rect.width),
          height: Math.round(rect.height),
          position: style.position,
          display: style.display,
          visibility: style.visibility,
        })
        if (hiddenOverflow.length >= 10) break
      }
      return {
        label: labelText,
        viewportWidth,
        refWidth,
        innerWidth: window.innerWidth,
        outerWidth: window.outerWidth,
        screenWidth: window.screen ? window.screen.width : null,
        devicePixelRatio: window.devicePixelRatio || 1,
        visualViewport: window.visualViewport ? {
          width: Math.round(window.visualViewport.width * 100) / 100,
          height: Math.round(window.visualViewport.height * 100) / 100,
          scale: Math.round(window.visualViewport.scale * 1000) / 1000,
        } : null,
        viewportMeta: (document.querySelector('meta[name="viewport"]') || {}).content || null,
        scrollWidth: documentElement.scrollWidth,
        clientWidth: documentElement.clientWidth,
        bodyScrollWidth: document.body ? document.body.scrollWidth : 0,
        bodyClientWidth: document.body ? document.body.clientWidth : 0,
        overflow: documentElement.scrollWidth > documentElement.clientWidth + 1,
        offenders,
        scrollContainers,
        maxRight,
        chains,
        hiddenOverflow,
      }
    }, [label])
    result.viewport = this.ctx.viewport
    result.activity = this.ctx.activity
    this.ctx.report.overflows.push(result)
    if (result.overflow) {
      warn(this.ctx.report, 'overflow:' + label, `scrollWidth=${result.scrollWidth} > clientWidth=${result.clientWidth}; offenders=${JSON.stringify(result.offenders.slice(0, 4))}`)
    } else {
      pass(this.ctx.report, 'overflow:' + label, `无横向溢出 (scrollWidth=${result.scrollWidth}, clientWidth=${result.clientWidth})`)
    }
    return result
  }

  /** Generic visible dialog / drawer detector. */
  async dialogState() {
    return await this.evalFn(() => {
      const native = [...document.querySelectorAll('dialog')].filter((dialog) => dialog.open).length
      const overlays = [...document.querySelectorAll('[role="dialog"],[aria-modal="true"],dialog')].filter((element) => {
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return rect.width > 60 && rect.height > 60 && style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0.05
      }).length
      const drawers = [...document.querySelectorAll('aside,[class*="drawer"],[class*="slide-over"],[class*="panel"]')].filter((element) => {
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return rect.width > 200 && rect.height > 200 && style.display !== 'none' && style.visibility !== 'hidden'
      })
      const text = document.body ? (document.body.innerText || '') : ''
      return {
        native,
        overlays,
        visiblePanels: drawers.length,
        count: Math.max(native, overlays),
        text,
      }
    })
  }

  async waitDialogOpen(label, { timeout = 10000 } = {}) {
    return await this.waitForFn(() => {
      const native = [...document.querySelectorAll('dialog')].filter((dialog) => dialog.open).length
      const overlays = [...document.querySelectorAll('[role="dialog"],[aria-modal="true"]')].filter((element) => {
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return rect.width > 60 && rect.height > 60 && style.display !== 'none' && style.visibility !== 'hidden'
      }).length
      return native + overlays > 0
    }, [], label, { timeout })
  }

  async waitDialogClosed(label, { timeout = 10000 } = {}) {
    return await this.waitForFn(() => {
      const native = [...document.querySelectorAll('dialog')].filter((dialog) => dialog.open).length
      const overlays = [...document.querySelectorAll('[role="dialog"],[aria-modal="true"]')].filter((element) => {
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return rect.width > 60 && rect.height > 60 && style.display !== 'none' && style.visibility !== 'hidden'
      }).length
      return native + overlays === 0
    }, [], label, { timeout })
  }

  dispose() {
    for (const [method, handler] of this.boundHandlers) {
      this.cdp.listeners.get(method)?.delete(handler)
    }
    this.boundHandlers = []
  }
}

/* ------------------------------------------------------------------ chrome lifecycle */
async function getFreePort() {
  return await new Promise((resolve, reject) => {
    const server = net.createServer()
    server.unref()
    server.on('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port
      server.close(() => resolve(port))
    })
  })
}

async function portResponds(port, timeoutMs = 1200) {
  try {
    const response = await fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(timeoutMs) })
    return response.ok
  } catch { return false }
}

async function logTail(file, lines = 25) {
  try {
    const content = await readFile(file, 'utf8')
    return content.trim().split(/\r?\n/).slice(-lines).join('\n')
  } catch { return '(no chrome log)' }
}

async function launchChrome(port, profileDir, logFile) {
  const args = [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profileDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    '--disable-extensions',
    '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding',
    '--mute-audio',
    '--remote-allow-origins=*',
    '--window-size=1440,900',
    'about:blank',
  ]
  const logFd = openSync(logFile, 'w')
  let child
  try {
    child = spawn(CHROME, args, { stdio: ['ignore', logFd, logFd], windowsHide: true })
  } finally {
    closeSync(logFd)
  }
  const state = { child, port, profileDir, logFile, exited: false, exitCode: null }
  child.on('exit', (code) => { state.exited = true; state.exitCode = code })
  child.on('error', (error) => { state.exited = true; state.spawnError = error.message })
  const deadline = Date.now() + 25000
  while (Date.now() < deadline) {
    if (state.spawnError) throw new Error('Chrome 启动失败: ' + state.spawnError)
    if (await portResponds(port)) return state
    await sleep(200)
  }
  const tail = await logTail(logFile)
  throw new Error(`Chrome DevTools 端口 ${port} 25s 内未就绪。stderr tail:\n${tail}`)
}

async function killChrome(state, cdp) {
  try { await cdp?.close() } catch { /* ignore */ }
  const child = state?.child
  if (!child || state.exited) return
  const exited = new Promise((resolve) => child.once('exit', resolve))
  await Promise.race([exited, sleep(4000)])
  if (state.exited) return
  console.log('[chrome] Browser.close 未退出，taskkill /T /F pid=' + child.pid)
  await new Promise((resolve) => {
    const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true })
    killer.on('exit', resolve)
    killer.on('error', resolve)
    setTimeout(resolve, 5000).unref?.()
  })
  await Promise.race([exited, sleep(2000)])
}

/* ------------------------------------------------------------------ app helpers */
function loginButtonsVisible(loginTexts) {
  return (texts) => {
    const norm = (value) => (value || '').replace(/\s+/g, ' ').trim()
    const nodes = [...document.querySelectorAll('button,a,[role="button"]')]
    return nodes.some((node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      if (rect.width <= 2 || rect.height <= 2 || style.visibility === 'hidden' || style.display === 'none') return false
      const text = norm(node.innerText || node.value)
      return texts.some((candidate) => text === candidate || text.includes(candidate))
    })
  }
}

async function probeEntryState(page, kind) {
  return await page.safeEvalFn((kindName) => {
    const text = document.body ? (document.body.innerText || '') : ''
    const norm = (value) => (value || '').replace(/\s+/g, ' ').trim()
    const visibleNodes = [...document.querySelectorAll('button,a,[role="button"]')].filter((node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return rect.width > 2 && rect.height > 2 && style.visibility !== 'hidden' && style.display !== 'none'
    })
    const loginTexts = ['使用单点登录', '单点登录', '统一身份登录', '企业登录', 'SSO 登录', '立即登录']
    const loginText = visibleNodes.map((node) => norm(node.innerText || node.value))
      .find((label) => loginTexts.some((candidate) => label === candidate || label.includes(candidate))) || null
    const shellRegex = kindName === 'admin'
      ? /文档列表|暂无文档|上传文档|上传第一份文档|文档管理|文档库|全部文档/
      : /你的问题|试问年假|提问|知识问答/
    const shellSpecific = kindName === 'admin'
      ? /文档列表|暂无文档|上传文档|上传第一份文档|文档库|全部文档|table/i.test(text)
      : /你的问题|试问年假|textarea|提问/i.test(text)
    return {
      hasLogin: !!loginText,
      loginText,
      booting: /正在连接/.test(text),
      session: Object.keys(sessionStorage || {}).some((key) => key.startsWith('oidc.user:')),
      shellText: shellRegex.test(text),
      shellSpecific,
      textLen: text.length,
    }
  }, [kind])
}

const SHELL_CHECK = {
  admin: () => {
    const text = document.body ? (document.body.innerText || '') : ''
    const login = [...document.querySelectorAll('button,a,[role="button"]')].some((node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return rect.width > 2 && rect.height > 2 && style.display !== 'none'
        && /使用单点登录|单点登录|统一身份登录|企业登录/.test((node.innerText || '').trim())
    })
    return !!text && !login && /文档列表|暂无文档|上传文档|上传第一份文档|文档库|全部文档|文档总数/.test(text)
  },
  ask: () => {
    const text = document.body ? (document.body.innerText || '') : ''
    const hasComposer = !!document.querySelector('#question, textarea')
    const login = [...document.querySelectorAll('button,a,[role="button"]')].some((node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return rect.width > 2 && rect.height > 2 && style.display !== 'none'
        && /使用单点登录|单点登录|统一身份登录|企业登录/.test((node.innerText || '').trim())
    })
    return !!text && !login && (hasComposer || /知识问答/.test(text))
  },
}

/** Robust OIDC login state machine: handles existing SSO cookies and fresh Keycloak forms. */
async function performLogin(page, kind, { timeout = 90000 } = {}) {
  const report = page.ctx.report
  const started = Date.now()
  const seen = { loginButtonClicked: false, keycloakReached: false, formFilled: false, submitClicked: false, redirectBack: false }
  let lastHref = ''
  let lastState = null
  const shellFn = SHELL_CHECK[kind]

  while (Date.now() - started < timeout) {
    let href
    try { href = await page.location() } catch { await sleep(150); continue }
    if (href !== lastHref) { lastHref = href; if (href.includes('/realms/') && !seen.keycloakReached) seen.keycloakReached = true }
    let url
    try { url = new URL(href) } catch { await sleep(150); continue }

    if (url.origin === APP_ORIGIN && url.pathname.startsWith('/' + kind)) {
      const state = await probeEntryState(page, kind)
      lastState = state
      if (!state) { await sleep(150); continue }
      if (state.session && !state.booting && !state.hasLogin && state.shellSpecific) {
        seen.redirectBack = true
        report.login = { kind, username: CREDS.username, ...seen, durationMs: Date.now() - started, url: href, ok: true }
        return report.login
      }
      if (state.hasLogin && !seen.loginButtonClicked) {
        seen.loginButtonClicked = true
        const clicked = await page.clickByText(LOGIN_TEXTS, { timeout: 10000 }).catch((error) => {
          throw new Error('点击登录按钮失败: ' + error.message)
        })
        pass(report, 'login:' + kind + ':screen', `登录门禁已出现，点击“${clicked.text}”`)
        await sleep(300)
        continue
      }
    } else if (url.origin === KEYCLOAK_ORIGIN) {
      if (!seen.keycloakReached) seen.keycloakReached = true
      const form = await page.safeEvalFn(() => {
        const username = document.querySelector('input#username')
        const password = document.querySelector('input#password')
        const button = document.querySelector('button#kc-login, input#kc-login')
        const text = document.body ? (document.body.innerText || '') : ''
        return { hasUsername: !!username, hasPassword: !!password, hasSubmit: !!button, text: text.slice(0, 400) }
      })
      if (form?.hasUsername) {
        if (!seen.formFilled) {
          await page.fill('input#username', CREDS.username)
          await page.fill('input#password', CREDS.password)
          seen.formFilled = true
          pass(report, 'login:' + kind + ':fill', '已填写 Keycloak 凭据 (' + CREDS.username + ')')
          await sleep(200)
          continue
        }
        if (!seen.submitClicked) {
          await page.clickSelector('button#kc-login, input#kc-login')
          seen.submitClicked = true
          pass(report, 'login:' + kind + ':submit', '已提交 Keycloak 登录表单')
          await sleep(300)
          continue
        }
        // Still on the form after submit: credential/validation failure.
        if (/无效|错误|Invalid|error/i.test(form.text)) {
          throw new Error('Keycloak 登录失败（表单仍停留在登录页）: ' + form.text.replace(/\s+/g, ' ').slice(0, 220))
        }
      }
    }
    await sleep(150)
  }
  throw new Error(`OIDC 登录超时 ${timeout}ms；最后 href=${lastHref}；阶段=${JSON.stringify(seen)}；最后状态=${JSON.stringify(lastState)}`)
}

async function waitForShell(page, kind, { timeout = 35000 } = {}) {
  const shellFn = SHELL_CHECK[kind]
  const source = `(${shellFn.toString()})()`
  const started = Date.now()
  let lastError = null
  while (Date.now() - started < timeout) {
    try {
      const ok = await page.eval(source)
      if (ok) return true
    } catch (error) { lastError = error }
    await sleep(150)
  }
  throw new Error(`等待已登录${kind === 'admin' ? '文档管理' : '知识问答'}界面超时: ${lastError ? lastError.message : ''}`)
}

/** Navigate to an app page and guarantee an authenticated shell. */
async function openApp(page, kind) {
  const report = page.ctx.report
  const target = kind === 'admin' ? ADMIN_URL : ASK_URL
  page.ctx.activity = kind + '-boot'
  await page.navigate(target)
  await page.waitForFn(() => {
    const app = document.querySelector('#app')
    return !!app && app.children.length > 0
  }, [], '应用挂载 (#app 有子节点)', { timeout: 30000 }).catch(async (error) => {
    const text = (await page.bodyText()).slice(0, 300)
    throw new Error(error.message + '；body=' + JSON.stringify(text))
  })

  // Either a login gate, an existing session, or a code-exchange in flight.
  const entryDeadline = Date.now() + 40000
  let entry = null
  while (Date.now() < entryDeadline) {
    entry = await probeEntryState(page, kind)
    if (!entry) { await sleep(150); continue }
    if (entry.hasLogin) break
    if (entry.session && !entry.booting && entry.shellSpecific) break
    await sleep(150)
  }
  if (!entry) throw new Error('页面状态探测失败')
  if (entry.hasLogin) {
    await performLogin(page, kind)
  } else if (!(entry.session && entry.shellSpecific)) {
    // Session exists but shell markers missing, or boot still racing: try login flow if a gate appears.
    await sleep(800)
    const retry = await probeEntryState(page, kind)
    if (retry?.hasLogin) await performLogin(page, kind)
  }
  await waitForShell(page, kind)
  page.ctx.activity = kind + '-ready'
  pass(report, 'shell:' + kind, kind === 'admin' ? '已登录文档管理界面' : '已登录知识问答界面')
  return entry
}

/* ------------------------------------------------------------------ stats helpers */
/* admin console stats for the NEW UI (Vue components in admin-web/src). */
async function adminStats(page) {
  return await page.evalFn(() => {
    const text = document.body ? (document.body.innerText || '') : ''
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    // DocumentsPanel.vue: section.panel.documents > h2#documents-heading 全部文档; rows tr.doc-row.
    const documentsRoot = document.querySelector('[aria-labelledby="documents-heading"], section.panel.documents, .documents')
    // UsagePanel.vue: section.usage > h1#usage-heading 租户用量.
    const usageRoot = document.querySelector('[aria-labelledby="usage-heading"], section.usage')
    const rows = documentsRoot ? [...documentsRoot.querySelectorAll('tr.doc-row, tbody tr')] : []
    const titles = rows.map((row) => {
      const strong = row.querySelector('.doc-cell__text strong, strong, button')
      return norm(strong?.innerText || row.innerText).slice(0, 80)
    }).filter(Boolean)
    const usageRows = usageRoot ? [...usageRoot.querySelectorAll('table tbody tr')].length : 0
    const statCards = [...document.querySelectorAll('.stat, .stat-card')].map((card) => ({
      label: norm(card.querySelector('.stat__label, .stat-card__label')?.innerText),
      value: norm(card.querySelector('.stat__value, .stat-card__value')?.innerText),
    }))
    const navItems = [...document.querySelectorAll('.sidebar .nav-item, .sidebar__nav a, .sidebar__nav button')]
      .map((element) => norm(element.innerText).slice(0, 20)).filter(Boolean)
    return {
      title: document.title,
      innerWidth: window.innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      view: usageRoot ? 'usage' : (documentsRoot ? 'documents' : 'unknown'),
      h1: norm(document.querySelector('h1')?.innerText),
      documentRows: rows.length,
      documentTitles: titles.slice(0, 6),
      usageRows,
      emptyState: /还没有文档|暂无文档/.test(text),
      skeleton: !!document.querySelector('.table-skeleton, .skeleton'),
      statCards,
      navItems: [...new Set(navItems)],
      bodyTextLen: text.length,
      bodyText: text.replace(/\s+/g, ' ').slice(0, 600),
      location: location.href,
    }
  })
}

/* ask page stats for the NEW UI (admin-web/src/Ask.vue + AnswerCard.vue). */
async function askStats(page) {
  return await page.evalFn(() => {
    const text = document.body ? (document.body.innerText || '') : ''
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const composer = document.querySelector('textarea#question, .composer textarea, textarea')
    const answers = [...document.querySelectorAll('article.answer, [class*="answer__card"]')]
    const composerBox = document.querySelector('.composer')
    const composerStyle = composerBox ? getComputedStyle(composerBox) : null
    return {
      title: document.title,
      composer: !!composer,
      composerPlaceholder: composer?.getAttribute('placeholder') || '',
      styles: {
        sheets: [...document.styleSheets].map((sheet) => sheet.href || '(inline)'),
        bodyFont: getComputedStyle(document.body).fontFamily,
        bodyBg: getComputedStyle(document.body).backgroundColor,
        composerBox: composerStyle ? {
          borderRadius: composerStyle.borderRadius,
          background: composerStyle.backgroundColor,
          padding: composerStyle.padding,
          width: Math.round(composerBox.getBoundingClientRect().width),
        } : null,
      },
      answers: answers.length,
      answerTextLen: answers.reduce((sum, element) => sum + norm(element.innerText).length, 0),
      citeChips: document.querySelectorAll('.cite-chip').length,
      sourceCards: document.querySelectorAll('.source-card').length,
      insufficient: /没有找到足够的依据/.test(text),
      pending: /正在生成|正在检索/.test(text),
      errorAlert: norm(document.querySelector('.answer__alert, .alert--danger')?.innerText),
      bodyTextLen: text.length,
      bodyText: text.replace(/\s+/g, ' ').slice(0, 600),
      location: location.href,
    }
  })
}

async function deployedAssets(page) {
  return await page.safeEvalFn(() => [...performance.getEntriesByType('resource')]
    .map((entry) => entry.name)
    .filter((name) => name.includes('/admin/assets/'))
    .map((name) => name.split('/admin/assets/')[1]), [])
}

/* ------------------------------------------------------------------ baseline phase */
async function runBaseline(page) {
  const report = page.ctx.report
  await page.setViewport(VIEWPORTS.desktop)

  await openApp(page, 'admin')
  await page.settle(700)

  const stats = await adminStats(page)
  report.pageStats.admin1440 = stats
  info(report, 'baseline:admin:content', `文档行数=${stats.documentRows}，用量行数=${stats.usageRows}，空状态=${stats.emptyState}，文档标题=${JSON.stringify(stats.documentTitles)}`)
  if (stats.documentRows > 0) pass(report, 'baseline:admin:seeded-rows', `${stats.documentRows} 行文档已呈现`)
  else warn(report, 'baseline:admin:seeded-rows', '文档列表为空（qa-api 可能仍在播种；基线不因此失败）')

  await page.screenshot('baseline-admin-1440')
  await page.overflowCheck('baseline-admin-1440')

  const assets = await deployedAssets(page)
  report.deployedAssets = assets || []
  info(report, 'baseline:deployed-assets', JSON.stringify((assets || []).slice(0, 8)))

  // mobile admin
  await page.setViewport(VIEWPORTS.mobile)
  await page.settle(700)
  report.pageStats.admin390 = await adminStats(page)
  await page.screenshot('baseline-admin-390')
  await page.overflowCheck('baseline-admin-390')

  // ask desktop
  await page.setViewport(VIEWPORTS.desktop)
  await openApp(page, 'ask')
  await page.settle(500)
  const ask = await askStats(page)
  report.pageStats.ask1440 = ask
  info(report, 'baseline:ask:content', `composer=${ask.composer}，回答数=${ask.answers}，bodyTextLen=${ask.bodyTextLen}`)
  await page.screenshot('baseline-ask-1440')
  await page.overflowCheck('baseline-ask-1440')

  // Serve evidence of the deployed OLD build hashes as well.
  try {
    const html = await (await fetch(ADMIN_URL, { signal: AbortSignal.timeout(8000) })).text()
    const scripts = [...html.matchAll(/src="([^"]+)"/g)].map((match) => match[1])
    const links = [...html.matchAll(/href="([^"]+\.css)"/g)].map((match) => match[1])
    report.servedIndex = { adminScripts: scripts, adminStyles: links, fetchedAt: new Date().toISOString() }
    info(report, 'baseline:served-index', JSON.stringify({ scripts, links }))
  } catch (error) {
    warn(report, 'baseline:served-index', '读取 /admin/ HTML 失败: ' + error.message)
  }
}

/* ------------------------------------------------------------------ new phase */
function guard(report, name, fn) {
  return (async () => {
    try {
      const detail = await fn()
      report.checks.push({ name, status: 'pass', detail: detail ?? '' })
      return true
    } catch (error) {
      report.checks.push({ name, status: 'fail', detail: error.message })
      console.error(`[CHECK FAIL] ${name} — ${error.message}`)
      return false
    }
  })()
}

async function openFirstDocument(page, report) {
  const result = await page.safeEvalFn(() => {
    const norm = (value) => (value || '').replace(/\s+/g, ' ').trim()
    // NEW UI: DocumentsPanel.vue root carries aria-labelledby="documents-heading";
    // rows are tr.doc-row and clicking the row (or its .doc-cell button) opens the drawer.
    const docRoot = document.querySelector('[aria-labelledby="documents-heading"], section.panel.documents, .documents')
      || [...document.querySelectorAll('section, main')].find((element) => /全部文档/.test(element.innerText || ''))
      || document.body
    const rows = [...docRoot.querySelectorAll('tr.doc-row, table tbody tr, [role="row"]')]
    for (const row of rows) {
      const text = norm(row.innerText)
      if (!text || /还没有文档|上传第一份文档|刷新|暂无文档/.test(text)) continue
      const clickable = row.querySelector('button.doc-cell, .doc-cell, button, a, [role="button"], [role="link"]') || row
      clickable.scrollIntoView({ block: 'center' })
      clickable.click()
      return { clicked: true, title: text.slice(0, 80), source: clickable === row ? 'row' : 'row-control', rowCount: rows.length }
    }
    return { clicked: false, rows: rows.length, hint: norm(docRoot.innerText).slice(0, 120) }
  })
  if (!result?.clicked) {
    throw new Error('文档列表中没有可点击的文档行 (rows=' + (result?.rows ?? 'unknown') + '; hint=' + JSON.stringify(result?.hint ?? '') + ')')
  }
  return result
}

async function closeDialogBestEffort(page, texts) {
  // First try Escape (native <dialog> cancel), then close/cancel buttons.
  await page.pressEscape()
  const closedAfterEscape = await page.safeEvalFn(() => {
    const native = [...document.querySelectorAll('dialog')].filter((dialog) => dialog.open).length
    const overlays = [...document.querySelectorAll('[role="dialog"],[aria-modal="true"]')].filter((element) => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return rect.width > 60 && rect.height > 60 && style.display !== 'none' && style.visibility !== 'hidden'
    }).length
    return { open: native + overlays > 0 }
  })
  if (closedAfterEscape && !closedAfterEscape.open) return 'Escape'
  await page.clickByText(texts, { timeout: 5000 }).catch(() => null)
  await page.waitDialogClosed('对话框关闭').catch(() => null)
  return 'button'
}

async function runNew(page) {
  const report = page.ctx.report
  await page.setViewport(VIEWPORTS.desktop)

  /* ---------- 1. admin dashboard @ three viewports ---------- */
  await openApp(page, 'admin')
  // NEW UI fetches /documents?page=0&size=100 after mount and renders .table-skeleton
  // first; wait for rows (or a real empty state) before asserting/capturing evidence.
  const docsLoaded = await page.waitForFn(() => {
    const rows = document.querySelectorAll('tr.doc-row').length
    const text = document.body ? (document.body.innerText || '') : ''
    return rows >= 3 || /还没有文档/.test(text)
  }, [], '文档列表加载完成（≥3 行）', { timeout: 30000 }).catch(() => false)
  await page.settle(900)
  const stats = await adminStats(page)
  report.pageStats.admin1440 = stats
  info(report, 'new:admin:content', `文档加载=${!!docsLoaded}，视图=${stats.view}，文档行数=${stats.documentRows}，标题=${JSON.stringify(stats.documentTitles)}，统计卡片=${JSON.stringify(stats.statCards)}`)

  await page.screenshot('admin-dashboard-1440')
  const overflow1440 = await page.overflowCheck('new-admin-1440')
  await guard(report, 'admin:authenticated-shell', async () => {
    if (!stats.bodyTextLen) throw new Error('页面无文本内容')
    return '已登录，body 文本长度 ' + stats.bodyTextLen + '，h1=' + JSON.stringify(stats.h1)
  })
  await guard(report, 'admin:stat-strip', async () => {
    const labels = (stats.statCards || []).map((card) => card.label)
    if (labels.length < 3) throw new Error('未找到统计卡片: ' + JSON.stringify(stats.statCards))
    if (!labels.some((label) => /文档|可检索/.test(label))) throw new Error('统计卡片缺少文档总数/可检索: ' + JSON.stringify(stats.statCards))
    return JSON.stringify(stats.statCards)
  })
  await guard(report, 'admin:seeded-documents', async () => {
    if (stats.documentRows < 3) throw new Error(`文档行数为 ${stats.documentRows}，期望至少 3 行种子文档; skeleton=${stats.skeleton}`)
    return `${stats.documentRows} 行：${JSON.stringify(stats.documentTitles)}`
  })
  await guard(report, 'admin:no-horizontal-overflow-1440', async () => {
    if (overflow1440.overflow) throw new Error(`scrollWidth=${overflow1440.scrollWidth} > clientWidth=${overflow1440.clientWidth}; offenders=${JSON.stringify(overflow1440.offenders.slice(0, 3))}`)
    return '无横向溢出'
  })
  // Evidence only: primary CTA hit-area sizes (a11y:primary-hit-size enforces >=44px on the ask page).
  const adminCta = await page.safeEvalFn(() => {
    const button = [...document.querySelectorAll('button')].find((element) => /上传文档/.test((element.innerText || '').trim()))
    if (!button) return null
    const rect = button.getBoundingClientRect()
    return { text: '上传文档', width: Math.round(rect.width), height: Math.round(rect.height) }
  })
  report.pageStats.adminCta = adminCta || null
  if (adminCta) info(report, 'admin:cta-size', `“上传文档”主按钮 ${adminCta.width}×${adminCta.height}px`)

  await page.setViewport(VIEWPORTS.mid)
  await page.settle(600)
  report.pageStats.admin1024 = await adminStats(page)
  await page.screenshot('admin-dashboard-1024')
  const overflow1024 = await page.overflowCheck('new-admin-1024')
  await guard(report, 'admin:no-horizontal-overflow-1024', async () => {
    if (overflow1024.overflow) throw new Error(`scrollWidth=${overflow1024.scrollWidth} > clientWidth=${overflow1024.clientWidth}`)
    return '无横向溢出'
  })

  await page.setViewport(VIEWPORTS.mobile)
  // Chrome can keep a stale layout viewport (innerWidth 485 for a 390px emulation) when the
  // device metrics change on an already-loaded desktop page; a reload re-parses the viewport
  // meta and yields the real mobile layout before we judge overflow.
  await page.reload()
  await waitForShell(page, 'admin', { timeout: 30000 }).catch(() => null)
  await page.waitForFn(() => {
    const rows = document.querySelectorAll('tr.doc-row').length
    return rows > 0 || /还没有文档/.test(document.body.innerText || '')
  }, [], '移动端文档列表恢复', { timeout: 15000 }).catch(() => null)
  await page.settle(800)
  report.pageStats.admin390 = await adminStats(page)
  await page.screenshot('admin-dashboard-390')
  const overflow390 = await page.overflowCheck('new-admin-390')
  await guard(report, 'admin:no-horizontal-overflow-390', async () => {
    if (overflow390.overflow) throw new Error(`scrollWidth=${overflow390.scrollWidth} > clientWidth=${overflow390.clientWidth}; offenders=${JSON.stringify(overflow390.offenders.slice(0, 3))}`)
    return '无横向溢出'
  })

  await page.setViewport(VIEWPORTS.desktop)
  await page.settle(500)

  /* ---------- 2. document detail drawer ---------- */
  await guard(report, 'admin:drawer-open', async () => {
    const opened = await openFirstDocument(page, report)
    await page.waitDialogOpen('文档抽屉', { timeout: 12000 })
    await page.waitForFn(() => !!document.querySelector('dialog.drawer[open]'), [], '版本记录抽屉打开', { timeout: 12000 })
    const drawerState = await page.evalFn(() => {
      const dialog = document.querySelector('dialog.drawer[open]')
      return {
        title: (dialog?.querySelector('.drawer__title')?.innerText || '').trim(),
        textLen: (dialog?.innerText || '').trim().length,
        text: (dialog?.innerText || '').replace(/\s+/g, ' ').slice(0, 240),
      }
    })
    if (!/文档详情|版本/.test(drawerState.title + drawerState.text)) throw new Error('抽屉标题不是文档详情: ' + JSON.stringify(drawerState))
    await page.settle(600)
    await page.screenshot('admin-document-drawer-1440')
    return '已打开：' + opened.title + '，抽屉标题=' + JSON.stringify(drawerState.title)
  })
  await guard(report, 'admin:document-content', async () => {
    const tabState = await page.evalFn(() => {
      const dialog = document.querySelector('dialog.drawer[open]')
      const norm = (value) => (value || '').replace(/\s+/g, ' ').trim()
      const buttons = [...(dialog?.querySelectorAll('button') ?? [])]
      const contentTab = buttons.find((button) => /文档内容/.test(norm(button.innerText)))
      return {
        found: !!contentTab,
        selected: contentTab ? contentTab.getAttribute('aria-selected') : null,
        text: norm(dialog?.innerText || '').slice(0, 240),
      }
    })
    if (!tabState.found) throw new Error('文档详情抽屉缺少「文档内容」Tab: ' + JSON.stringify(tabState))
    await page.clickByText(['文档内容'], { timeout: 8000 })
    const preview = await page.waitForFn(() => {
      const text = (document.querySelector('dialog.drawer[open] pre[data-doc-preview]')?.innerText || '').trim()
      return text.length > 40 ? { length: text.length, head: text.slice(0, 80) } : false
    }, [], '文档正文预览渲染', { timeout: 20000 })
    const meta = await page.evalFn(() => {
      const dialog = document.querySelector('dialog.drawer[open]')
      const norm = (value) => (value || '').replace(/\s+/g, ' ').trim()
      const download = [...(dialog?.querySelectorAll('button') ?? [])].find((button) => /下载原文件/.test(norm(button.innerText)))
      return {
        download: norm(download?.innerText || ''),
        meta: norm(dialog?.querySelector('.detail-meta')?.innerText || '').slice(0, 240),
      }
    })
    if (!meta.download) throw new Error('文档内容页缺少「下载原文件」按钮')
    await page.settle(500)
    await page.screenshot('admin-document-content-1440')
    return '「文档内容」Tab(aria-selected=' + JSON.stringify(tabState.selected) + ') 正文 ' + preview.length + ' 字，预览=' + JSON.stringify(preview.head) + '，下载按钮=' + JSON.stringify(meta.download) + '，元数据=' + JSON.stringify(meta.meta)
  })
  await guard(report, 'admin:drawer-close', async () => {
    const how = await closeDialogBestEffort(page, ['关闭', '取消', '返回'])
    await page.waitDialogClosed('抽屉关闭', { timeout: 8000 })
    return '关闭方式：' + how
  })

  /* ---------- 3. upload dialog + validation + close ---------- */
  await guard(report, 'admin:upload-dialog-open', async () => {
    await page.clickByText(['上传文档', '新建文档'], { timeout: 12000 })
    await page.waitForFn(() => {
      const dialog = document.querySelector('dialog.dialog[open], dialog[open]')
      return !!dialog && /上传文档/.test(dialog.innerText || '')
    }, [], '上传对话框内容', { timeout: 10000 })
    await page.settle(400)
    await page.screenshot('admin-upload-dialog-1440')
    return '上传对话框已打开（h2 上传文档）'
  })
  await guard(report, 'admin:upload-file-input', async () => {
    const input = await page.evalFn(() => {
      const fileInput = document.querySelector('dialog[open] input[type="file"], input.visually-hidden[type="file"], input[type="file"]')
      const titleInput = document.querySelector('dialog[open] input.field__input[type="text"], dialog[open] input[type="text"]')
      return fileInput ? {
        accept: fileInput.getAttribute('accept') || '',
        hidden: fileInput.classList.contains('visually-hidden'),
        hasTitleInput: !!titleInput,
        titlePlaceholder: titleInput?.getAttribute('placeholder') || '',
      } : null
    })
    if (!input) throw new Error('上传对话框中未找到 input[type=file]')
    if (!/\.docx/i.test(input.accept) || !/\.pdf/i.test(input.accept) || !/\.txt/i.test(input.accept)) {
      throw new Error('文件输入 accept 不含 .docx/.pdf/.txt: ' + JSON.stringify(input.accept))
    }
    if (!input.hasTitleInput) throw new Error('未找到文档标题输入框')
    return JSON.stringify(input)
  })
  await guard(report, 'admin:upload-validation', async () => {
    await page.evalFn(() => {
      const dialog = document.querySelector('dialog[open]')
      const buttons = [...(dialog ? dialog.querySelectorAll('button') : document.querySelectorAll('button'))]
      const submit = buttons.find((button) => /上传并解析|提交文档|提交上传|提交/.test((button.innerText || '').trim()))
      if (submit) { submit.click(); return true }
      return false
    })
    await page.waitForFn(() => {
      const dialog = document.querySelector('dialog[open]')
      const alerts = [...(dialog ? dialog.querySelectorAll('.alert--danger, [role="alert"]') : [])]
      return alerts.some((alert) => /请输入|请选择|不能为空|仅支持|不超过/.test(alert.innerText || ''))
    }, [], '校验错误提示', { timeout: 10000 })
    await page.settle(300)
    await page.screenshot('admin-upload-validation-1440')
    const messages = await page.safeEvalFn(() => [...document.querySelectorAll('dialog[open] .alert--danger, dialog[open] [role="alert"]')]
      .map((alert) => (alert.innerText || '').replace(/\s+/g, ' ').trim()).filter(Boolean))
    return '空提交触发校验：' + JSON.stringify(messages)
  })
  await guard(report, 'admin:upload-dialog-close', async () => {
    const how = await closeDialogBestEffort(page, ['取消', '关闭'])
    await page.waitDialogClosed('上传对话框关闭', { timeout: 8000 })
    return '关闭方式：' + how
  })

  /* ---------- 4. usage view ---------- */
  await guard(report, 'admin:usage-view', async () => {
    const clicked = await page.evalFn(() => {
      const items = [...document.querySelectorAll('.nav-item, .sidebar__nav button, .sidebar__nav a')]
      const target = items.find((item) => /用量分析/.test((item.innerText || '').trim()))
      if (!target) return false
      target.scrollIntoView({ block: 'center' })
      target.click()
      return true
    })
    if (!clicked) throw new Error('未找到侧栏“用量分析”导航项')
    await page.waitForFn(() => {
      const heading = document.querySelector('h1#usage-heading')
      return !!heading && /租户用量/.test(heading.innerText || '')
    }, [], '用量视图渲染', { timeout: 15000 })
    // The usage panel fetches /usage after mount; wait for rows or the explicit empty message
    // instead of judging the still-loading skeleton (which shows 0 tokens / 共 0 个模型分组).
    await page.waitForFn(() => {
      const section = document.querySelector('[aria-labelledby="usage-heading"], section.usage')
      if (!section) return false
      const rows = section.querySelectorAll('table tbody tr').length
      return rows > 0 || /当日暂无模型调用/.test(section.innerText || '')
    }, [], '用量数据加载完成', { timeout: 15000 })
    await page.settle(300)
    const usage = await page.evalFn(() => {
      const section = document.querySelector('[aria-labelledby="usage-heading"], section.usage')
      const table = section?.querySelector('table.usage-table, table')
      const headers = table ? [...table.querySelectorAll('thead th')].map((th) => (th.innerText || '').trim()) : []
      const date = section?.querySelector('input[type="date"]')
      const rows = table ? table.querySelectorAll('tbody tr').length : 0
      const purposes = table ? [...table.querySelectorAll('tbody tr')].map((row) => (row.children[1]?.innerText || '').trim()).filter(Boolean) : []
      return { headers, rows, purposes, hasDate: !!date, text: (section?.innerText || '').replace(/\s+/g, ' ').slice(0, 220) }
    })
    await page.settle(500)
    await page.screenshot('admin-usage-1440')
    report.pageStats.usage = usage
    if (!usage.headers.length && !/暂无模型调用|暂无模型/.test(usage.text)) throw new Error('用量视图未渲染表格或空状态: ' + JSON.stringify(usage))
    if (usage.headers.length && !usage.headers.some((header) => /模型|输入|估算费用/.test(header))) throw new Error('用量表头缺失: ' + JSON.stringify(usage.headers))
    if (!usage.hasDate) throw new Error('用量视图缺少日期选择控件')
    return `表头=${JSON.stringify(usage.headers)}，行数=${usage.rows}，用途=${JSON.stringify(usage.purposes)}，日期控件=${usage.hasDate}`
  })
  await guard(report, 'admin:usage-purpose-labels', async () => {
    const purposes = report.pageStats.usage?.purposes || []
    const raw = purposes.filter((purpose) => /^(chat|embedding|generation|rerank|query)$/i.test(purpose))
    if (raw.length) throw new Error('用途列仍显示原始英文值: ' + JSON.stringify(raw))
    return '用途标签=' + JSON.stringify(purposes)
  })

  /* ---------- 5. sidebar / nav switching ---------- */
  await guard(report, 'admin:nav-switching', async () => {
    const nav = await page.evalFn(() => [...document.querySelectorAll('.sidebar .nav-item, .sidebar__nav a, .sidebar__nav button')]
      .filter((element) => {
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return rect.width > 20 && rect.height > 20 && style.display !== 'none' && style.visibility !== 'hidden'
      })
      .map((element) => (element.innerText || '').replace(/\s+/g, ' ').trim())
      .filter((text) => text && text.length < 24))
    const unique = [...new Set(nav)]
    for (const wanted of ['文档库', '用量分析']) {
      if (!unique.includes(wanted)) throw new Error('侧栏导航缺少“' + wanted + '”: ' + JSON.stringify(unique))
    }
    await page.evalFn(() => {
      const target = [...document.querySelectorAll('.sidebar .nav-item, .sidebar__nav a, .sidebar__nav button')]
        .find((item) => /文档库/.test((item.innerText || '').trim()))
      target?.click()
      return !!target
    })
    await page.waitForFn(() => {
      const heading = document.querySelector('h2#documents-heading')
      return !!heading && /全部文档/.test(heading.innerText || '')
    }, [], '切回文档库视图', { timeout: 10000 })
    await page.settle(500)
    const heading = await page.safeEvalFn(() => (document.querySelector('h1')?.innerText || '').trim())
    await page.screenshot('admin-nav-1440')
    report.pageStats.nav = { items: unique, heading }
    return '导航项：' + JSON.stringify(unique) + '，当前视图=' + JSON.stringify(heading)
  })

  /* ---------- 5b. mobile sidebar: collapsed at 390px, .topbar__menu toggles it ---------- */
  await guard(report, 'admin:mobile-nav', async () => {
    await page.setViewport(VIEWPORTS.mobile)
    await page.reload()
    await waitForShell(page, 'admin', { timeout: 30000 }).catch(() => null)
    await page.waitForFn(() => {
      const rows = document.querySelectorAll('tr.doc-row').length
      return rows > 0 || /还没有文档/.test(document.body.innerText || '')
    }, [], '移动端文档列表恢复', { timeout: 15000 }).catch(() => null)
    await page.settle(700)
    const menuPresent = await page.safeEvalFn(() => !!document.querySelector('.topbar__menu'))
    if (!menuPresent) throw new Error('390px 下未找到 .topbar__menu 打开导航按钮')
    await page.evalFn(() => { document.querySelector('.topbar__menu')?.click(); return true })
    await page.waitForFn(() => {
      const shell = document.querySelector('.shell')
      const sidebar = document.querySelector('.sidebar')
      if (!shell || !sidebar) return false
      const rect = sidebar.getBoundingClientRect()
      return shell.classList.contains('shell--nav-open') && rect.left > -10 && rect.width > 100
    }, [], '移动端侧栏展开', { timeout: 8000 })
    await page.settle(400)
    const navText = await page.evalFn(() => [...document.querySelectorAll('.sidebar .nav-item, .sidebar__nav a, .sidebar__nav button')]
      .map((item) => (item.innerText || '').trim()).filter(Boolean))
    await page.screenshot('admin-nav-mobile-390')
    await page.evalFn(() => { document.querySelector('.shell__overlay')?.click(); return true })
    await page.waitForFn(() => !document.querySelector('.shell')?.classList.contains('shell--nav-open'), [], '移动端侧栏关闭', { timeout: 8000 }).catch(() => null)
    await page.setViewport(VIEWPORTS.desktop)
    await page.settle(500)
    return '移动端导航项=' + JSON.stringify(navText)
  })

  /* ---------- 6. ask page @ 1440 + 390 ---------- */
  await page.setViewport(VIEWPORTS.desktop)
  await openApp(page, 'ask')
  await page.waitForFn(() => !!document.querySelector('textarea#question, .composer textarea'), [], '问答输入框出现', { timeout: 30000 })
  await page.settle(600)
  const ask = await askStats(page)
  report.pageStats.ask1440 = ask
  await page.screenshot('ask-1440')
  const askOverflow1440 = await page.overflowCheck('new-ask-1440')
  await guard(report, 'ask:composer', async () => {
    if (!ask.composer) throw new Error('未找到提问输入框')
    if (!/入职满一年能休几天年假/.test(ask.composerPlaceholder)) throw new Error('输入框 placeholder 未包含示例问题: ' + JSON.stringify(ask.composerPlaceholder))
    return '输入框 placeholder=' + JSON.stringify(ask.composerPlaceholder) + '，h1=' + JSON.stringify((await page.safeEvalFn(() => (document.querySelector('h1')?.innerText || '').trim())) || '')
  })
  await guard(report, 'ask:page-styled', async () => {
    const sheets = (ask.styles && ask.styles.sheets) || []
    const hasAskCss = sheets.some((href) => /\/Ask-[^/]+\.css$/.test(href))
    const box = ask.styles && ask.styles.composerBox
    if (!hasAskCss) throw new Error('问答页组件 CSS 未加载（Ask-*.css 缺失），页面呈未样式化状态；已加载样式表=' + JSON.stringify(sheets))
    if (!box || box.borderRadius === '0px' || box.width < 600) throw new Error('问答输入卡未应用组件样式：' + JSON.stringify(box))
    return '已加载问答页样式，输入卡 ' + JSON.stringify(box)
  })
  await guard(report, 'ask:no-horizontal-overflow-1440', async () => {
    if (askOverflow1440.overflow) throw new Error(`scrollWidth=${askOverflow1440.scrollWidth} > clientWidth=${askOverflow1440.clientWidth}`)
    return '无横向溢出'
  })

  await page.setViewport(VIEWPORTS.mobile)
  await page.settle(600)
  await page.screenshot('ask-390')
  const askOverflow390 = await page.overflowCheck('new-ask-390')
  await guard(report, 'ask:no-horizontal-overflow-390', async () => {
    if (askOverflow390.overflow) throw new Error(`scrollWidth=${askOverflow390.scrollWidth} > clientWidth=${askOverflow390.clientWidth}`)
    return '无横向溢出'
  })
  await page.setViewport(VIEWPORTS.desktop)
  await page.settle(400)

  /* ---------- 7. submit seeded question, wait for answer ---------- */
  const question = '入职满一年能休几天年假？'
  let answerReady = false
  let submitLatencyMs = 0
  await guard(report, 'ask:submit-question', async () => {
    const started = Date.now()
    await page.fill('#question', question)
    await page.waitForFn(() => {
      const button = document.querySelector('.composer .btn--primary, .composer button[type="submit"]')
      return !!button && !button.disabled
    }, [], '提问按钮可点击', { timeout: 8000 })
    await page.evalFn(() => {
      const button = document.querySelector('.composer .btn--primary, .composer button[type="submit"]')
      button?.click()
      return !!button
    })
    await page.waitForFn(() => {
      const text = document.body ? document.body.innerText : ''
      const answerBody = document.querySelector('.answer__body, .answer__card .answer__body')
      const empty = document.querySelector('.answer__empty')
      const alert = document.querySelector('.answer__alert')
      const pendingCard = document.querySelector('.pending-card, .answer__loading')
      const pending = !!pendingCard || /正在生成|正在检索/.test(text)
      const answerText = answerBody ? (answerBody.innerText || '').trim() : ''
      return !pending && (answerText.length > 20 || !!empty || !!alert)
    }, [], '回答生成完成', { timeout: 120000 })
    submitLatencyMs = Date.now() - started
    answerReady = true
    report.pageStats.submitLatencyMs = submitLatencyMs
    await page.settle(800)
    await page.screenshot('ask-answer-1440')
    return `已回答问题（${Math.round(submitLatencyMs / 1000)}s）：` + question
  })
  await guard(report, 'ask:answer-content', async () => {
    const state = await page.evalFn(() => {
      const text = document.body ? document.body.innerText : ''
      const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
      return {
        answerLen: norm(document.querySelector('.answer__body')?.innerText).length,
        citeChips: document.querySelectorAll('.cite-chip').length,
        sourceCards: document.querySelectorAll('.source-card').length,
        insufficient: /没有找到足够的依据/.test(text),
        alert: norm(document.querySelector('.answer__alert')?.innerText).slice(0, 240),
        head: norm(document.querySelector('.answer__head')?.innerText).slice(0, 120),
      }
    })
    report.pageStats.answer = state
    if (state.alert) throw new Error('回答生成失败（界面错误提示）: ' + state.alert)
    if (!state.answerLen) throw new Error('回答卡片为空; insufficient=' + state.insufficient)
    if (state.insufficient) throw new Error('回答为“没有找到足够的依据”，未检索到种子文档（检索/索引问题）')
    if (!state.citeChips && !state.sourceCards) throw new Error('回答没有引用来源: ' + JSON.stringify(state))
    return `answerLen=${state.answerLen}, citeChips=${state.citeChips}, sourceCards=${state.sourceCards}, head=${JSON.stringify(state.head)}`
  })

  /* ---------- 8. citation -> source drawer ---------- */
  if (answerReady) {
    await guard(report, 'ask:citation-drawer', async () => {
      const clicked = await page.safeEvalFn(() => {
        const candidates = [...document.querySelectorAll('.cite-chip, .source-card, .answer__sources button')]
        const visible = candidates.filter((element) => {
          const rect = element.getBoundingClientRect()
          const style = getComputedStyle(element)
          return rect.width > 5 && rect.height > 5 && style.display !== 'none' && style.visibility !== 'hidden'
        })
        if (!visible.length) return false
        visible[0].scrollIntoView({ block: 'center' })
        visible[0].click()
        return true
      })
      if (!clicked) throw new Error('未找到可点击的引用（.cite-chip/.source-card）')
      await page.waitForFn(() => !!document.querySelector('dialog.drawer[open]'), [], '引用原文抽屉打开', { timeout: 15000 })
      await page.waitForFn(() => {
        const dialog = document.querySelector('dialog.drawer[open]')
        return !!dialog && /引用原文/.test(dialog.innerText || '')
      }, [], '引用抽屉标题', { timeout: 15000 })
      await page.waitForFn(() => !!document.querySelector('dialog.drawer[open] .source-view__text, dialog.drawer[open] pre'), [], '引用原文加载', { timeout: 20000 })
      await page.waitForFn(() => !!document.querySelector('dialog.drawer[open] mark'), [], '引用高亮', { timeout: 20000 })
      await page.settle(500)
      await page.screenshot('ask-citation-drawer-1440')
      const state = await page.evalFn(() => {
        const dialog = document.querySelector('dialog.drawer[open]')
        const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
        return {
          title: norm(dialog?.querySelector('.drawer__title')?.innerText),
          mark: norm(dialog?.querySelector('mark')?.innerText).slice(0, 120),
          textLen: norm(dialog?.querySelector('.source-view__text, pre')?.innerText).length,
          wide: !!dialog?.classList.contains('drawer--wide'),
        }
      })
      report.pageStats.citationDrawer = state
      if (!state.mark) throw new Error('引用抽屉中未找到 <mark> 高亮')
      return '引用抽屉 title=' + JSON.stringify(state.title) + '，高亮=' + JSON.stringify(state.mark)
    })
    await guard(report, 'ask:citation-drawer-close', async () => {
      const how = await closeDialogBestEffort(page, ['关闭', '取消', '返回'])
      await page.waitDialogClosed('引用抽屉关闭', { timeout: 8000 })
      return '关闭方式：' + how
    })
  }

  /* ---------- 9. accessibility: focus visibility + primary CTA hit size ---------- */
  await guard(report, 'a11y:focus-visible', async () => {
    await page.evalFn(() => { document.activeElement?.blur(); return true })
    let result = null
    for (let attempt = 0; attempt < 6; attempt += 1) {
      await page.pressTab()
      result = await page.evalFn(() => {
        const element = document.activeElement
        if (!element || element === document.body) return null
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        const outlineWidth = parseFloat(style.outlineWidth || '0') || 0
        return {
          tag: element.tagName.toLowerCase(),
          text: (element.innerText || element.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 40),
          width: Math.round(rect.width),
          height: Math.round(rect.height),
          outlineStyle: style.outlineStyle,
          outlineWidth,
          boxShadow: style.boxShadow,
          visible: rect.width > 0 && rect.height > 0,
        }
      })
      if (result?.visible) break
    }
    if (!result) throw new Error('Tab 未聚焦到任何可见元素')
    const hasFocusRing = (result.outlineStyle !== 'none' && result.outlineWidth > 0)
      || (result.boxShadow && result.boxShadow !== 'none')
    if (!hasFocusRing) throw new Error('焦点元素无可见 outline/box-shadow: ' + JSON.stringify(result))
    report.pageStats.focus = result
    return `焦点=${result.tag} “${result.text}”, outline=${result.outlineStyle}/${result.outlineWidth}px, boxShadow=${result.boxShadow?.slice(0, 40)}`
  })

  await guard(report, 'a11y:primary-hit-size', async () => {
    const buttons = await page.evalFn(() => {
      const wanted = /提问|上传文档|提交文档|上传并解析|开始提问|发送/
      return [...document.querySelectorAll('button, [role="button"], a[class*="primary"]')]
        .filter((element) => {
          const rect = element.getBoundingClientRect()
          const style = getComputedStyle(element)
          return rect.width > 2 && rect.height > 2 && style.display !== 'none' && style.visibility !== 'hidden'
            && (wanted.test((element.innerText || '').trim()) || /primary|btn-primary/i.test(String(element.className || '')))
        })
        .map((element) => {
          const rect = element.getBoundingClientRect()
          return {
            text: (element.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 30),
            width: Math.round(rect.width),
            height: Math.round(rect.height),
          }
        })
    })
    if (!buttons?.length) throw new Error('未找到主 CTA 按钮')
    report.pageStats.primaryButtons = buttons
    const small = buttons.filter((button) => button.height < 44 || button.width < 44)
    if (small.length) throw new Error('主 CTA 命中区域小于 44px: ' + JSON.stringify(small))
    return JSON.stringify(buttons)
  })

  /* ---------- 10. design token snapshot (evidence) ---------- */
  await guard(report, 'design:tokens', async () => {
    const tokens = await page.evalFn(() => {
      const pick = (element) => {
        if (!element) return null
        const style = getComputedStyle(element)
        return {
          fontFamily: style.fontFamily,
          fontSize: style.fontSize,
          color: style.color,
          background: style.backgroundColor,
          borderRadius: style.borderRadius,
          boxShadow: style.boxShadow,
          maxWidth: style.maxWidth,
        }
      }
      const body = document.body
      const primary = [...document.querySelectorAll('button, [role="button"]')].find((element) => /提问|上传文档|提交/.test((element.innerText || '').trim()))
      return {
        body: pick(body),
        h1: pick(document.querySelector('h1')),
        primaryCta: pick(primary),
        surface: pick(document.querySelector('section, article, [class*="surface"], [class*="card"]')),
      }
    })
    report.pageStats.designTokens = tokens
    return JSON.stringify(tokens?.body?.background) + ' / ' + JSON.stringify(tokens?.primaryCta?.background)
  })
}

/* ------------------------------------------------------------------ admin phase: 管理 pages */

const ADMIN_VIEWS = [
  { key: 'evaluation', label: 'RAGAS 评测', heading: ['RAGAS 评测', '评测'] },
  { key: 'monitoring', label: '系统监控', heading: ['系统监控', '监控'] },
  { key: 'access', label: '用户与权限', heading: ['用户与权限', '权限'] },
]
const KNOWN_SERVICES = [
  'gateway', 'doc-service', 'search-service', 'ai-service', 'metric-service',
  'keycloak', 'opensearch', 'nacos', 'model-primary', 'model-backup',
]

/** Sidebar nav items (+ active state) for the admin shell. */
async function adminNavState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const sidebar = document.querySelector('.sidebar, aside[aria-label="主导航"], aside')
    const nodes = sidebar ? [...sidebar.querySelectorAll('a, button, [role="link"], [role="button"]')] : []
    const items = nodes
      .filter((node) => {
        const rect = node.getBoundingClientRect()
        const style = getComputedStyle(node)
        return rect.width > 2 && rect.height > 2 && style.display !== 'none' && style.visibility !== 'hidden'
      })
      .map((node) => {
        const cls = typeof node.className === 'string' ? node.className : ''
        return {
          text: norm(node.innerText).slice(0, 40),
          active: /(^|[\s_-])(active|current|selected)/i.test(cls)
            || node.getAttribute('aria-current') === 'page'
            || node.getAttribute('aria-selected') === 'true',
          href: node.getAttribute('href') || '',
        }
      })
      .filter((item) => item.text && item.text.length < 30)
    const text = norm(sidebar ? sidebar.innerText : document.body.innerText)
    return { items, hasManageSection: /管理/.test(text), text: text.slice(0, 800) }
  })
}

/**
 * Click a sidebar nav item by label. At <=1024px the sidebar is off-canvas, so open it via
 * .topbar__menu first, click, then dismiss the overlay (App.vue @media max-width:1024px).
 */
async function clickAdminNav(page, label) {
  const mobileLayout = await page.safeEvalFn(() => window.innerWidth <= 1024)
  if (mobileLayout) {
    await page.evalFn(() => { document.querySelector('.topbar__menu')?.click(); return true })
    await page.waitForFn(() => {
      const shell = document.querySelector('.shell')
      const sidebar = document.querySelector('.sidebar')
      if (!sidebar) return false
      const rect = sidebar.getBoundingClientRect()
      return (shell && shell.classList.contains('shell--nav-open')) || rect.left > -10
    }, [], '移动端侧栏展开', { timeout: 8000 }).catch(() => null)
    await sleep(250)
  }
  const result = await page.evalFn((wanted) => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const nodes = [...document.querySelectorAll('.sidebar a, .sidebar button, .sidebar [role="button"], .sidebar [role="link"], aside a, aside button')]
    const visible = nodes.filter((node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return rect.width > 2 && rect.height > 2 && style.display !== 'none' && style.visibility !== 'hidden'
    })
    const target = visible.find((node) => norm(node.innerText) === wanted)
      || visible.find((node) => norm(node.innerText).includes(wanted))
    if (!target) return { ok: false, candidates: visible.map((node) => norm(node.innerText)).filter(Boolean).slice(0, 30) }
    target.scrollIntoView({ block: 'center' })
    target.click()
    return { ok: true, text: norm(target.innerText).slice(0, 40) }
  }, [label])
  if (!result?.ok) throw new Error('侧栏未找到导航项“' + label + '”：' + JSON.stringify(result?.candidates || []))
  await sleep(300)
  if (mobileLayout) {
    await page.evalFn(() => { const overlay = document.querySelector('.shell__overlay'); if (overlay) overlay.click(); return true }).catch(() => null)
    await sleep(200)
  }
  return result
}

/** Wait until an <h1>/<h2> (page head) matches one of the keywords. */
async function waitAdminHeading(page, keywords, label = '管理页面标题', { timeout = 25000 } = {}) {
  return await page.waitForFn((words) => {
    const headings = [...document.querySelectorAll('h1, h2, [class*="page-head"] h1, [class*="page-head"] h2')]
    const text = headings.map((heading) => heading.innerText || '').join(' ')
    return words.some((word) => text.includes(word))
  }, [keywords], label, { timeout })
}

/** Snapshot of the current admin sub-page: h1, breadcrumb, doc title, hash, KPI cards. */
async function adminPageState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const h1 = [...document.querySelectorAll('h1')].map((node) => norm(node.innerText)).filter(Boolean)[0] || ''
    const breadcrumbNode = document.querySelector('.breadcrumb, [class*="breadcrumb"], [class*="crumb"]')
    const cards = [...document.querySelectorAll('[class*="kpi"], .stat-card, .stat, [class*="metric-card"], .eval-gate__item')].filter((node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return rect.width > 30 && rect.height > 24 && style.display !== 'none' && style.visibility !== 'hidden'
    })
    const labels = cards.map((card) => norm(card.innerText).slice(0, 48)).filter(Boolean)
    const text = norm(document.body.innerText)
    return {
      h1,
      breadcrumb: breadcrumbNode ? norm(breadcrumbNode.innerText).slice(0, 160) : '',
      docTitle: document.title,
      hash: location.hash,
      kpiCount: cards.length,
      kpiLabels: labels.slice(0, 10),
      text: text.slice(0, 1000),
    }
  })
}

/** Reports table on the RAGAS evaluation page (headers are Chinese: 报告 / 可信度 / 召回). */
async function evalTableState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const tables = [...document.querySelectorAll('table')]
    const table = document.querySelector('table.eval-table')
      || tables.find((candidate) => /可信度/.test(candidate.innerText || '') && /(报告|召回)/.test(candidate.innerText || ''))
    const rows = table ? [...table.querySelectorAll('tbody tr')] : []
    const text = norm(document.body.innerText)
    const totalNode = document.querySelector('.eval-history .count-chip, .eval-history [class*="count"]')
    return {
      found: !!table,
      headers: table ? [...table.querySelectorAll('thead th')].map((th) => norm(th.innerText)).filter(Boolean) : [],
      rows: rows.length,
      total: totalNode ? Number(norm(totalNode.innerText).replace(/\D+/g, '')) : null,
      rowsText: rows.map((row) => norm(row.innerText)).filter(Boolean).slice(0, 4),
      reportIds: [...new Set(text.match(/report-\d+/g) || [])].slice(0, 8),
      emptyText: /还没有评测记录|还没有|暂无|尚无/.test(text),
      bodyText: text.slice(0, 700),
    }
  })
}

/** Monitoring page snapshot: KPIs, status chips, 采集时间, governance strip markers. */
async function monitoringState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const visible = (node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return rect.width > 2 && rect.height > 2 && style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0.05
    }
    const text = norm(document.body.innerText)
    const statusCandidates = [...document.querySelectorAll('*')]
      .filter((node) => {
        const value = norm(node.innerText)
        if (!/^(正常|降级|不可用|UP|DEGRADED|DOWN)$/i.test(value)) return false
        if (!visible(node)) return false
        // Keep only the leaf-most node: wrappers inheriting the same text are not separate entries.
        return ![...node.children].some((child) => norm(child.innerText) === value)
      })
      .map((node) => norm(node.innerText))
    const serviceNodes = [...document.querySelectorAll('[class*="service"], [class*="svc"], [class*="endpoint"]')]
      .filter(visible)
      .filter((node) => norm(node.innerText).length > 4)
    const cards = [...document.querySelectorAll('[class*="kpi"], .stat-card, .stat, .metric-card')]
      .filter(visible)
      .map((card) => norm(card.innerText).slice(0, 48))
      .filter(Boolean)
    const heroMetaNode = document.querySelector('.monitor-hero__meta, [class*="hero__meta"]')
    const heroMeta = heroMetaNode ? norm(heroMetaNode.innerText).slice(0, 140) : ''
    const serviceRowCount = [...document.querySelectorAll('.service, li[class*="service"], [class*="service-row"]')].filter(visible).length
    return {
      text: text.slice(0, 1400),
      statusTexts: [...new Set(statusCandidates)].slice(0, 14),
      statusCount: statusCandidates.length,
      serviceNodeCount: serviceNodes.length,
      serviceRowCount,
      kpiCount: cards.length,
      kpiLabels: cards.slice(0, 12),
      heroMeta,
      checkedAt: heroMeta,
      hasRefreshCue: /采集/.test(heroMeta),
      hasAutoRefresh: /自动刷新/.test(text),
    }
  })
}

/** Users + role matrix on the access page. */
async function accessState(page, usernames) {
  return await page.evalFn((wanted) => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const visible = (node) => {
      const rect = node.getBoundingClientRect()
      const style = getComputedStyle(node)
      return rect.width > 2 && rect.height > 2 && style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0.05
    }
    const text = norm(document.body.innerText)
    const tables = [...document.querySelectorAll('table')]
    const table = tables.find((candidate) => /用户|邮箱|用户名|ADMIN|角色/i.test(candidate.innerText || ''))
    const rows = table ? [...table.querySelectorAll('tbody tr')] : []
    const chips = [...document.querySelectorAll('[class*="chip"], [class*="badge"], [class*="tag"], [class*="pill"]')]
      .filter(visible)
      .map((node) => norm(node.innerText))
      .filter(Boolean)
    const roleCardNodes = [...document.querySelectorAll('.role-card, [class*="role-card"]')]
      .filter(visible)
      .filter((node) => /(^|\s)role-card(\s|$)/.test(String(node.className || '')))
    const roleCardTexts = roleCardNodes.map((node) => norm(node.innerText))
    const roleCodeNodes = [...document.querySelectorAll('.role-card__code, [class*="role-card__code"]')].filter(visible)
    const roleCodes = [...new Set(
      roleCodeNodes.map((node) => norm(node.innerText)).concat(chips.filter((chip) => /^(ADMIN|EMPLOYEE)$/i.test(chip))),
    )].slice(0, 6)
    const roleCards = {
      admin: roleCardTexts.some((value) => /ADMIN/.test(value) && /文档上传|版本发布|评测|权限管理/.test(value)),
      employee: roleCardTexts.some((value) => /EMPLOYEE/.test(value) && /知识库问答|引用原文核对|个人会话追溯/.test(value)),
    }
    return {
      found: !!table,
      headers: table ? [...table.querySelectorAll('thead th')].map((th) => norm(th.innerText)).filter(Boolean) : [],
      rows: rows.length,
      rowsText: rows.map((row) => norm(row.innerText)).filter(Boolean).slice(0, 8),
      usernames: wanted.filter((name) => text.includes(name)),
      tenantChips: ['tenant-a', 'tenant-b'].filter((name) => text.includes(name)),
      roleChips: roleCodes,
      roleCardCount: roleCardNodes.length,
      roleCards,
      counts: {
        hasTotal: /用户总数|用户数/.test(text),
        hasTenants: /租户数/.test(text),
      },
      text: text.slice(0, 1600),
    }
  }, [usernames])
}

/** Scroll the first report row into view and click it; returns row text for evidence. */
async function clickFirstEvalReport(page) {
  return await page.safeEvalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const table = document.querySelector('table.eval-table') || [...document.querySelectorAll('table')]
      .find((candidate) => /可信度/.test(candidate.innerText || '') && /(报告|召回)/.test(candidate.innerText || ''))
    const row = table?.querySelector('tbody tr')
    if (!row) return { clicked: false }
    row.scrollIntoView({ block: 'center' })
    const control = row.querySelector('.eval-table__report, button, a, [role="button"], [role="link"]') || row
    control.click()
    return { clicked: true, text: norm(row.innerText).slice(0, 90) }
  })
}

async function runAdmin(page) {
  const report = page.ctx.report
  report.pageStats.adminPages = {}
  const evalPosts = []
  const monitoringGets = []
  page.on('Network.requestWillBeSent', (params) => {
    const method = String(params.request?.method || '').toUpperCase()
    const url = String(params.request?.url || '')
    if (method === 'POST' && /\/api\/v1\/evals(\?|$)/.test(url)) evalPosts.push({ method, url, at: new Date().toISOString() })
    if (/\/api\/v1\/monitoring(\?|$)/.test(url)) monitoringGets.push({ method, url, at: new Date().toISOString() })
  })

  await page.setViewport(VIEWPORTS.desktop)
  page.ctx.activity = 'admin:shell'
  await openApp(page, 'admin')
  // The 管理 sidebar section is data-driven (only renders for ADMIN); give the shell a moment.
  await page.waitForFn(() => {
    const text = document.body ? document.body.innerText : ''
    return /RAGAS/.test(text) && /系统监控/.test(text) && /用户与权限/.test(text)
  }, [], '侧栏管理导航出现', { timeout: 30000 }).catch(() => null)
  await page.settle(600)

  const nav = await adminNavState(page)
  report.pageStats.adminNav = nav
  await guard(report, 'admin:nav-manage-section', async () => {
    const labels = nav.items.map((item) => item.text)
    for (const wanted of ['RAGAS 评测', '系统监控', '用户与权限']) {
      if (!labels.some((label) => label.includes(wanted))) throw new Error('侧栏缺少“' + wanted + '”导航项：' + JSON.stringify(labels))
    }
    if (!nav.hasManageSection) throw new Error('侧栏缺少“管理”分组标题文本')
    return '管理侧栏项：' + JSON.stringify(labels.slice(0, 12))
  })

  /* ---------- RAGAS 评测 ---------- */
  page.ctx.activity = 'admin:evaluation'
  await guard(report, 'admin:eval-nav', async () => {
    await clickAdminNav(page, 'RAGAS 评测')
    await waitAdminHeading(page, ADMIN_VIEWS[0].heading, 'RAGAS 评测页面标题')
    await page.settle(500)
    const state = await adminPageState(page)
    report.pageStats.adminPages.evaluation = { nav: state }
    const navState = await adminNavState(page)
    const target = navState.items.find((item) => item.text.includes('RAGAS 评测'))
    const breadcrumbOk = /评测/.test(state.breadcrumb) || /评测/.test(state.docTitle)
    if (!target) throw new Error('侧栏中未找到 RAGAS 评测项')
    if (!target.active) throw new Error('RAGAS 评测导航项未显示 active 状态：' + JSON.stringify(navState.items))
    if (!breadcrumbOk) throw new Error('面包屑/页面标题未更新为评测视图：breadcrumb=' + JSON.stringify(state.breadcrumb) + ' title=' + JSON.stringify(state.docTitle))
    return 'h1=' + JSON.stringify(state.h1) + '，breadcrumb=' + JSON.stringify(state.breadcrumb) + '，hash=' + JSON.stringify(state.hash)
  })

  await guard(report, 'admin:eval-kpi', async () => {
    const state = await adminPageState(page)
    report.pageStats.adminPages.evaluation.kpi = { count: state.kpiCount, labels: state.kpiLabels }
    if (state.kpiCount < 4) throw new Error('KPI 卡片不足 4 个：' + JSON.stringify(state.kpiLabels))
    if (!state.kpiLabels.some((label) => /可信度|Faithfulness/i.test(label))) throw new Error('KPI 缺少可信度/Faithfulness：' + JSON.stringify(state.kpiLabels))
    if (!state.kpiLabels.some((label) => /上下文召回|Context Recall/i.test(label))) throw new Error('KPI 缺少上下文召回/Context Recall：' + JSON.stringify(state.kpiLabels))
    return JSON.stringify(state.kpiLabels)
  })

  await guard(report, 'admin:eval-reports', async () => {
    // Wait until the archived table is stable (skeleton gone) before deciding it is empty —
    // an early snapshot could otherwise trigger a spurious 运行评测 POST.
    await page.waitForFn(() => {
      const skeleton = document.querySelector('.table-skeleton')
      const table = document.querySelector('table.eval-table')
      const rows = table ? table.querySelectorAll('tbody tr').length : 0
      const body = document.body ? document.body.innerText : ''
      return !skeleton && (rows >= 1 || /还没有评测记录/.test(body))
    }, [], '评测报告加载', { timeout: 60000, interval: 250 }).catch(() => null)
    await page.settle(400)
    let state = await evalTableState(page)
    if (state.rows < 1) {
      // No archived report yet: run one evaluation so the list has evidence (golden dataset).
      const seeded = await page.clickByText(['运行评测'], { timeout: 15000 }).then(() => true).catch(() => false)
      if (!seeded) throw new Error('评测报告表格为空且未找到“运行评测”按钮：' + JSON.stringify(state.bodyText))
      await page.waitForFn(() => {
        const table = document.querySelector('table.eval-table')
        const rows = table ? table.querySelectorAll('tbody tr').length : 0
        return rows >= 1
      }, [], '评测报告加载', { timeout: 120000, interval: 500 })
      state = await evalTableState(page)
    }
    await page.waitForFn(() => !/进行中|正在评测|运行中/.test(document.body ? document.body.innerText : ''), [], '评测空闲', { timeout: 60000 }).catch(() => null)
    if (!state.found) throw new Error('未找到评测报告表格（table.eval-table）：' + JSON.stringify(state.bodyText))
    if (state.rows < 1) throw new Error('评测报告表格无数据行：' + JSON.stringify(state.bodyText))
    report.pageStats.adminPages.evaluation.table = state
    return '表头=' + JSON.stringify(state.headers) + '，行数=' + state.rows + '，总数=' + state.total + '，报告=' + JSON.stringify(state.reportIds)
  })

  // Ensure no detail drawer is left open before the clean list screenshot.
  await closeDialogBestEffort(page, ['关闭', '取消', '返回'])
  await page.waitForFn(() => !document.querySelector('dialog[open]'), [], '评测页抽屉关闭', { timeout: 8000 }).catch(() => null)
  await page.settle(400)
  await page.screenshot('admin-eval-1440')
  const overflowEval1440 = await page.overflowCheck('admin-eval-1440')
  report.pageStats.adminPages.evaluation.overflow1440 = { scrollWidth: overflowEval1440.scrollWidth, clientWidth: overflowEval1440.clientWidth, overflow: overflowEval1440.overflow }
  await guard(report, 'admin:eval-overflow-1440', async () => {
    if (overflowEval1440.overflow) throw new Error(`scrollWidth=${overflowEval1440.scrollWidth} > clientWidth=${overflowEval1440.clientWidth}; offenders=${JSON.stringify(overflowEval1440.offenders.slice(0, 3))}`)
    return '无横向溢出'
  })

  await guard(report, 'admin:eval-chart', async () => {
    const chart = await page.evalFn(() => {
      const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
      const panel = document.querySelector('.eval-chart')
      if (!panel) return { present: false }
      const svg = panel.querySelector('.eval-chart__svg')
      const polylines = svg ? [...svg.querySelectorAll('polyline')] : []
      const rect = svg ? svg.getBoundingClientRect() : { width: 0, height: 0 }
      return {
        present: true,
        svg: { width: Math.round(rect.width), height: Math.round(rect.height), ariaLabel: svg ? String(svg.getAttribute('aria-label') || '') : '' },
        polylines: polylines.map((line) => ({
          cls: String(line.getAttribute('class') || '').slice(0, 60),
          points: norm(line.getAttribute('points')),
          stroke: getComputedStyle(line).stroke,
          strokeWidth: getComputedStyle(line).strokeWidth,
        })),
        legend: norm(panel.querySelector('.eval-chart__legend')?.innerText),
        legendSwatches: [...panel.querySelectorAll('.eval-chart__legend i, .eval-chart__legend span')]
          .map((node) => getComputedStyle(node).backgroundColor || getComputedStyle(node).borderTopColor)
          .filter((color) => color && color !== 'rgba(0, 0, 0, 0)'),
        donutValue: norm(panel.querySelector('.eval-pass__value')?.textContent),
        donutArcStroke: panel.querySelector('.eval-pass__arc') ? getComputedStyle(panel.querySelector('.eval-pass__arc')).stroke : '',
      }
    })
    report.pageStats.adminPages.evaluation.chart = chart
    if (!chart.present) throw new Error('评测页未找到趋势面板 .eval-chart')
    if (!chart.svg || chart.svg.width < 200 || chart.svg.height < 60) throw new Error('趋势图 SVG 未渲染出有效尺寸：' + JSON.stringify(chart.svg))
    if (chart.polylines.length < 2) throw new Error('趋势图 polyline 数量不足 2 条：' + JSON.stringify(chart.polylines))
    const emptyLines = chart.polylines.filter((line) => line.points.split(/\s+/).filter(Boolean).length < 2)
    if (emptyLines.length) throw new Error('趋势图 polyline 的 points 为空/过短：' + JSON.stringify(emptyLines))
    for (const label of ['可信度', '上下文召回', '门禁阈值']) {
      if (!chart.legend.includes(label)) throw new Error('趋势图图例缺少「' + label + '」：' + JSON.stringify(chart.legend))
    }
    if (!/^\d{1,3}%$/.test(chart.donutValue)) throw new Error('门禁通过率 donut 数值格式不符（期望 /^\\d{1,3}%$/）：' + JSON.stringify(chart.donutValue))
    return `polyline=${chart.polylines.length}，legend=${JSON.stringify(chart.legend)}，donut=${chart.donutValue}，svg=${chart.svg.width}x${chart.svg.height}`
  })

  await guard(report, 'admin:eval-drawer', async () => {
    const opened = await clickFirstEvalReport(page)
    if (!opened?.clicked) throw new Error('没有可点击的评测报告行')
    await page.waitDialogOpen('评测报告抽屉', { timeout: 15000 })
    await page.waitForFn(() => !!document.querySelector('dialog.drawer[open], dialog[open]'), [], '版本/报告抽屉打开', { timeout: 15000 })
    await page.waitForFn(() => {
      const dialog = document.querySelector('dialog.drawer[open], dialog[open]')
      const text = dialog ? (dialog.innerText || '') : ''
      return /Faithfulness|Context Recall|样本|report-/i.test(text)
    }, [], '评测报告抽屉内容', { timeout: 20000 })
    await page.settle(500)
    const state = await page.evalFn(() => {
      const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
      const dialog = document.querySelector('dialog.drawer[open], dialog[open]')
      const rows = dialog ? dialog.querySelectorAll('tbody tr').length : 0
      const sampleLike = dialog ? dialog.querySelectorAll('li.sample, .sample-list li, [class*="sample"] li').length : 0
      return {
        title: norm(dialog?.querySelector('.drawer__title')?.innerText),
        sampleRows: Math.max(rows, sampleLike),
        hasAverages: /可信度|上下文召回|门禁/.test(norm(dialog?.innerText)),
        text: norm(dialog?.innerText).slice(0, 400),
      }
    })
    if (!state.hasAverages) throw new Error('评测报告抽屉缺少平均值区块（可信度/上下文召回/门禁）：' + JSON.stringify(state))
    if (state.sampleRows < 1) throw new Error('评测报告抽屉中样本行不足 1 行：' + JSON.stringify(state))
    await page.screenshot('admin-eval-drawer-1440')
    report.pageStats.adminPages.evaluation.drawer = state
    return '样本行=' + state.sampleRows + '，抽屉标题=' + JSON.stringify(state.title)
  })

  await guard(report, 'admin:eval-drawer-charts', async () => {
    const charts = await page.evalFn(() => {
      const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
      const dialog = document.querySelector('dialog.drawer[open], dialog[open]')
      const block = dialog ? dialog.querySelector('.eval-detail__charts') : null
      if (!block) return { present: false }
      const dots = [...block.querySelectorAll('.scatter-dot')]
      const bars = [...block.querySelectorAll('.hist-bar')]
      const svgs = [...block.querySelectorAll('svg')]
      const rectInfo = (element) => {
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return { width: Math.round(rect.width), height: Math.round(rect.height), fill: style.fill, opacity: style.fillOpacity }
      }
      return {
        present: true,
        svgCount: svgs.length,
        scatterDots: dots.length,
        scatterDotsRendered: dots.filter((dot) => { const rect = dot.getBoundingClientRect(); return rect.width > 0 && rect.height > 0 }).length,
        scatterDotColors: [...new Set(dots.map((dot) => getComputedStyle(dot).fill))],
        scatterDotSample: dots.slice(0, 2).map(rectInfo),
        histBars: bars.length,
        histBarsRendered: bars.filter((bar) => { const rect = bar.getBoundingClientRect(); return rect.width > 1 && rect.height > 0 }).length,
        histBarRect: bars.map(rectInfo),
        charts: svgs.map((svg) => ({
          ariaLabel: norm(svg.getAttribute('aria-label')),
          titleCount: svg.querySelectorAll('title').length,
          titleSample: [...svg.querySelectorAll('title')].map((title) => norm(title.textContent)).filter(Boolean).slice(0, 2),
          width: Math.round(svg.getBoundingClientRect().width),
          height: Math.round(svg.getBoundingClientRect().height),
        })),
      }
    })
    report.pageStats.adminPages.evaluation.drawerCharts = charts
    if (!charts.present) throw new Error('评测报告抽屉未找到图表区块 .eval-detail__charts')
    if (charts.scatterDots < 3) throw new Error('样本分布散点不足 3 个 .scatter-dot：' + JSON.stringify(charts))
    if (charts.scatterDotsRendered < 3) throw new Error('样本分布散点未实际渲染（可见尺寸为 0）：' + JSON.stringify(charts))
    if (charts.histBars !== 5) throw new Error('可信度直方图 hist-bar 数量不是 5：' + String(charts.histBars))
    if (!charts.histBarsRendered) throw new Error('可信度直方图柱子未实际渲染（可见尺寸为 0）')
    const labelled = charts.charts.filter((entry) => entry.ariaLabel || entry.titleCount > 0)
    if (charts.charts.length < 2 || labelled.length < 2) throw new Error('图表缺少 aria-label/title 标签：' + JSON.stringify(charts.charts))
    const blank = charts.charts.filter((entry) => entry.width < 100 || entry.height < 40)
    if (blank.length) throw new Error('抽屉图表 SVG 尺寸过小（可能空白）：' + JSON.stringify(blank))
    return `scatter=${charts.scatterDots}/${charts.scatterDotsRendered}，hist=${charts.histBars}/${charts.histBarsRendered}，charts=${charts.svgCount}，titles=${JSON.stringify(charts.charts.map((entry) => entry.titleCount))}`
  })

  await guard(report, 'admin:eval-drawer-close', async () => {
    const how = await closeDialogBestEffort(page, ['关闭', '取消', '返回'])
    await page.waitDialogClosed('评测报告抽屉关闭', { timeout: 8000 })
    return '关闭方式：' + how
  })

  await guard(report, 'admin:eval-run', async () => {
    await page.waitForFn(() => !/进行中|正在评测|运行中/.test(document.body ? document.body.innerText : ''), [], '评测空闲', { timeout: 60000 }).catch(() => null)
    const before = await evalTableState(page)
    const postsBefore = evalPosts.length
    const started = Date.now()
    await page.clickByText(['运行评测'], { timeout: 15000 })
    const afterRows = await page.waitForFn((prev) => {
      const table = document.querySelector('table.eval-table')
      const rows = table ? table.querySelectorAll('tbody tr').length : 0
      const chip = document.querySelector('.eval-history .count-chip')
      const total = chip ? Number(String(chip.innerText).replace(/[^0-9]/g, '')) || 0 : 0
      if (rows > prev.rows) return { rows, total }
      if (total > prev.total) return { rows, total }
      return false
    }, [before], '评测报告列表新增行', { timeout: 120000, interval: 500 })
    const latencyMs = Date.now() - started
    // 评测完成后 UI 会自动打开详情抽屉，先关闭，避免后续 Tab 焦点被困在抽屉里
    await page.waitForFn(() => !!document.querySelector('dialog.drawer[open], dialog[open]'), [], '评测详情抽屉打开', { timeout: 30000 }).catch(() => null)
    await closeDialogBestEffort(page, ['关闭', '取消', '返回'])
    await page.waitForFn(() => !document.querySelector('dialog[open]'), [], '评测详情抽屉关闭', { timeout: 10000 }).catch(() => null)
    const newPosts = evalPosts.slice(postsBefore)
    report.pageStats.adminPages.evaluation.run = { beforeRows: before.rows, beforeTotal: before.total, afterRows: afterRows.rows, afterTotal: afterRows.total, latencyMs, posts: newPosts }
    if (!newPosts.length) throw new Error('未观察到 POST /api/v1/evals 网络请求')
    return `POST ${newPosts.length} 次，报告行数/总数 ${before.rows}/${before.total}→${afterRows.rows}/${afterRows.total}，用时 ${Math.round(latencyMs / 1000)}s`
  })

  /* ---------- a11y on the evaluation page (primary CTA) ---------- */
  await guard(report, 'admin:eval-focus-visible', async () => {
    await page.evalFn(() => { document.activeElement?.blur(); return true })
    let result = null
    for (let attempt = 0; attempt < 8; attempt += 1) {
      await page.pressTab()
      result = await page.evalFn(() => {
        const element = document.activeElement
        if (!element || element === document.body) return null
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return {
          tag: element.tagName.toLowerCase(),
          text: (element.innerText || element.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 40),
          width: Math.round(rect.width),
          height: Math.round(rect.height),
          outlineStyle: style.outlineStyle,
          outlineWidth: parseFloat(style.outlineWidth || '0') || 0,
          boxShadow: style.boxShadow,
          visible: rect.width > 0 && rect.height > 0,
        }
      })
      if (result?.visible) break
    }
    if (!result) throw new Error('Tab 未聚焦到任何可见元素')
    const hasFocusRing = (result.outlineStyle !== 'none' && result.outlineWidth > 0) || (result.boxShadow && result.boxShadow !== 'none')
    if (!hasFocusRing) throw new Error('焦点元素无可见 outline/box-shadow: ' + JSON.stringify(result))
    report.pageStats.adminPages.evaluation.focus = result
    return `焦点=${result.tag} “${result.text}”，outline=${result.outlineStyle}/${result.outlineWidth}px`
  })

  await guard(report, 'admin:eval-primary-cta-44', async () => {
    const buttons = await page.evalFn(() => {
      return [...document.querySelectorAll('button.btn--primary, .btn--primary')]
        .filter((element) => {
          const rect = element.getBoundingClientRect()
          const style = getComputedStyle(element)
          return rect.width > 2 && rect.height > 2 && style.display !== 'none' && style.visibility !== 'hidden'
        })
        .map((element) => {
          const rect = element.getBoundingClientRect()
          return { text: (element.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 30), width: Math.round(rect.width), height: Math.round(rect.height) }
        })
    })
    if (!buttons?.length) throw new Error('评测页未找到 .btn--primary 主 CTA 按钮')
    report.pageStats.adminPages.evaluation.primaryButtons = buttons
    const runButton = buttons.find((button) => /运行评测/.test(button.text))
    if (!runButton) throw new Error('未找到“运行评测”主按钮：' + JSON.stringify(buttons))
    const small = buttons.filter((button) => button.height < 44)
    if (small.length) throw new Error('主 CTA 高度小于 44px: ' + JSON.stringify(small))
    return JSON.stringify(buttons)
  })

  /* ---------- 系统监控 ---------- */
  page.ctx.activity = 'admin:monitoring'
  await guard(report, 'admin:monitoring-page', async () => {
    await clickAdminNav(page, '系统监控')
    await waitAdminHeading(page, ADMIN_VIEWS[1].heading, '系统监控页面标题')
    await page.waitForFn(() => {
      const text = document.body ? document.body.innerText : ''
      return /正常|降级|不可用|UP|DEGRADED/.test(text) && /采集|更新/.test(text)
    }, [], '监控数据加载', { timeout: 30000 }).catch(() => null)
    await page.settle(600)
    const before = await monitoringState(page)
    const metaBefore = before.heroMeta
    const getsBefore = monitoringGets.length
    await page.clickByText(['刷新'], { timeout: 10000 }).catch(() => null)
    const refreshedMeta = await page.waitForFn((prev) => {
      const node = document.querySelector('.monitor-hero__meta, [class*="hero__meta"]')
      const now = node ? String(node.innerText || '').replace(/\s+/g, ' ').trim() : ''
      return now && now !== prev ? now : false
    }, [metaBefore], '采集信息刷新', { timeout: 8000, interval: 300 }).catch(() => null)
    const newGets = monitoringGets.slice(getsBefore)
    const refreshToast = await page.safeEvalFn(() => /已刷新/.test(document.body ? document.body.innerText : ''))
    const after = await monitoringState(page)
    const serviceIds = KNOWN_SERVICES.filter((id) => after.text.toLowerCase().includes(id))
    const serviceEntries = Math.max(serviceIds.length, after.serviceNodeCount, after.serviceRowCount, after.statusCount)
    const governance = ['输入 token', '输出 token', '模型切换', 'kafka', '索引失败']
      .filter((label) => after.text.toLowerCase().includes(label))
    // Relative label (刚刚采集/N 秒前采集) may stay identical across a fast refresh; accept a
    // new /api/v1/monitoring request or the 已刷新 toast as proof the refresh actually fired.
    const refreshed = !!(refreshedMeta || newGets.length || refreshToast)
    report.pageStats.adminPages.monitoring = {
      kpi: { count: after.kpiCount, labels: after.kpiLabels },
      statusTexts: after.statusTexts,
      statusCount: after.statusCount,
      serviceNodeCount: after.serviceNodeCount,
      serviceRowCount: after.serviceRowCount,
      serviceIds,
      serviceEntries,
      governance: { hasStrip: /治理/.test(after.text), labels: governance },
      autoRefresh: after.hasAutoRefresh,
      checkedBefore: metaBefore,
      checkedAfter: after.heroMeta,
      refreshed,
      monitoringGets: newGets.map((item) => item.url),
      refreshToast: !!refreshToast,
    }
    await page.settle(300)
    await page.screenshot('admin-monitoring-1440')
    const overflow = await page.overflowCheck('admin-monitoring-1440')
    report.pageStats.adminPages.monitoring.overflow1440 = { scrollWidth: overflow.scrollWidth, clientWidth: overflow.clientWidth, overflow: overflow.overflow }
    if (after.kpiCount < 4) throw new Error('监控 KPI 卡片不足 4 个：' + JSON.stringify(after.kpiLabels))
    if (serviceEntries < 9) throw new Error('服务条目不足 9 个：serviceIds=' + JSON.stringify(serviceIds) + '，statusCount=' + after.statusCount + '，serviceNodeCount=' + after.serviceNodeCount + '，serviceRowCount=' + after.serviceRowCount)
    if (!/正常|UP/.test(after.text)) throw new Error('未看到“正常/UP”状态：' + after.text.slice(0, 300))
    if (!/治理/.test(after.text) || governance.length < 2) throw new Error('治理指标 strip 缺失：' + JSON.stringify(governance))
    if (!refreshed) throw new Error('点击刷新后未观察到刷新动作（采集信息 before=' + JSON.stringify(metaBefore) + '，after=' + JSON.stringify(after.heroMeta) + '，monitoringGets=' + newGets.length + '，toast=' + !!refreshToast + '）')
    return '服务条目=' + after.serviceRowCount + '（ids=' + JSON.stringify(serviceIds) + '，status节点=' + after.statusCount + '，探测最大值=' + serviceEntries + '），KPI=' + after.kpiCount + '，治理指标=' + JSON.stringify(governance) + '，采集信息 ' + JSON.stringify(metaBefore) + '→' + JSON.stringify(after.heroMeta) + '，刷新请求=' + newGets.length + '，toast=' + !!refreshToast
  })
  await guard(report, 'admin:monitoring-overflow-1440', async () => {
    const overflow = report.pageStats.adminPages.monitoring.overflow1440
    if (overflow?.overflow) throw new Error(`scrollWidth=${overflow.scrollWidth} > clientWidth=${overflow.clientWidth}`)
    return '无横向溢出'
  })

  /* ---------- 用户与权限 ---------- */
  page.ctx.activity = 'admin:access'
  await guard(report, 'admin:access-page', async () => {
    await clickAdminNav(page, '用户与权限')
    await waitAdminHeading(page, ADMIN_VIEWS[2].heading, '用户与权限页面标题')
    await page.waitForFn(() => {
      const text = document.body ? document.body.innerText : ''
      return /a-admin/.test(text) && /ADMIN/.test(text)
    }, [], '用户目录加载', { timeout: 30000 }).catch(() => null)
    await page.settle(500)
    const state = await accessState(page, ['a-admin', 'a-employee', 'b-admin', 'b-employee'])
    report.pageStats.adminPages.access = state
    await page.screenshot('admin-access-1440')
    const overflow = await page.overflowCheck('admin-access-1440')
    state.overflow1440 = { scrollWidth: overflow.scrollWidth, clientWidth: overflow.clientWidth, overflow: overflow.overflow }
    if (!state.found) throw new Error('未找到用户表格')
    if (state.rows < 4) throw new Error('用户行数 ' + state.rows + ' < 4：' + JSON.stringify(state.rowsText))
    const missing = ['a-admin', 'a-employee', 'b-admin', 'b-employee'].filter((name) => !state.usernames.includes(name))
    if (missing.length) throw new Error('缺少用户：' + JSON.stringify(missing))
    if (state.tenantChips.length < 2) throw new Error('缺少租户 chips：' + JSON.stringify(state.tenantChips))
    if (state.roleChips.length < 2) throw new Error('缺少角色 chips（ADMIN/EMPLOYEE）：' + JSON.stringify(state.roleChips))
    if (!state.roleCards.admin || !state.roleCards.employee) {
      throw new Error('角色权限矩阵不完整（ADMIN 管理能力 / EMPLOYEE 问答能力）：' + JSON.stringify({ cards: state.roleCards, cardCount: state.roleCardCount }))
    }
    return '用户=' + JSON.stringify(state.usernames) + '，行数=' + state.rows + '，租户=' + JSON.stringify(state.tenantChips) + '，角色=' + JSON.stringify(state.roleChips) + '，矩阵卡片=' + state.roleCardCount
  })
  await guard(report, 'admin:access-overflow-1440', async () => {
    const overflow = report.pageStats.adminPages.access.overflow1440
    if (overflow?.overflow) throw new Error(`scrollWidth=${overflow.scrollWidth} > clientWidth=${overflow.clientWidth}`)
    return '无横向溢出'
  })

  /* ---------- responsive sweep: 1024 + 390 across all three views ---------- */
  const sweepViewport = async (viewport, suffix) => {
    await page.setViewport(viewport)
    await page.reload()
    await waitForShell(page, 'admin', { timeout: 30000 }).catch(() => null)
    await page.settle(800)
    const results = []
    for (const view of ADMIN_VIEWS) {
      page.ctx.activity = 'admin:' + view.key + ':' + suffix
      await clickAdminNav(page, view.label)
      await waitAdminHeading(page, view.heading, view.label + ' 标题 @' + suffix, { timeout: 25000 })
      await page.settle(500)
      const overflow = await page.overflowCheck(view.key + '-' + suffix)
      let chartPlot = null
      if (view.key === 'evaluation') {
        chartPlot = await page.safeEvalFn(() => {
          const plot = document.querySelector('.eval-chart__plot')
          if (!plot) return { present: false }
          const rect = plot.getBoundingClientRect()
          return { present: true, width: Math.round(rect.width), scrollWidth: plot.scrollWidth, clientWidth: plot.clientWidth }
        })
      }
      let deepOffenders = null
      if (overflow.overflow) {
        deepOffenders = await page.safeEvalFn(() => {
          const ref = Math.min(window.innerWidth, document.documentElement.clientWidth) || 390
          const nodes = [...document.querySelectorAll('body *')]
            .map((el) => {
              const rect = el.getBoundingClientRect()
              const style = getComputedStyle(el)
              return { el, rect, style, desc: el.querySelectorAll('*').length }
            })
            .filter(({ rect, style }) => rect.width > 1 && rect.height > 1 && style.display !== 'none'
              && style.visibility !== 'hidden' && rect.right > ref + 1)
            .sort((a, b) => a.desc - b.desc || b.rect.right - a.rect.right)
          return nodes.slice(0, 8).map(({ el, rect, style, desc }) => {
            const parent = el.parentElement
            const parentStyle = parent ? getComputedStyle(parent) : null
            const parentRect = parent ? parent.getBoundingClientRect() : null
            return {
              tag: el.tagName.toLowerCase(),
              cls: String(el.className || '').slice(0, 70),
              width: Math.round(rect.width),
              right: Math.round(rect.right),
              depth: el.querySelectorAll('*').length,
              minWidth: style.minWidth,
              whiteSpace: style.whiteSpace,
              overflow: style.overflow,
              text: (el.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 50),
              parent: parent ? {
                tag: parent.tagName.toLowerCase(),
                cls: String(parent.className || '').slice(0, 70),
                width: Math.round(parentRect.width),
                scrollWidth: parent.scrollWidth,
                clientWidth: parent.clientWidth,
                display: parentStyle.display,
                gridTemplateColumns: parentStyle.gridTemplateColumns,
                gap: parentStyle.gap,
                minWidth: parentStyle.minWidth,
              } : null,
            }
          })
        })
      }
      results.push({
        view: view.key,
        innerWidth: overflow.innerWidth,
        outerWidth: overflow.outerWidth,
        screenWidth: overflow.screenWidth,
        visualViewport: overflow.visualViewport,
        scrollWidth: overflow.scrollWidth,
        clientWidth: overflow.clientWidth,
        bodyScrollWidth: overflow.bodyScrollWidth,
        bodyClientWidth: overflow.bodyClientWidth,
        overflow: overflow.overflow,
        chartPlot,
        offenders: overflow.offenders.slice(0, 12),
        hiddenOverflow: overflow.hiddenOverflow,
        maxRight: overflow.maxRight,
        chains: (overflow.chains || []).slice(0, 2),
        deepOffenders,
      })
      if (view.key === 'monitoring' && suffix === '390') await page.screenshot('admin-monitoring-390')
    }
    return results
  }

  const sweep1024 = await sweepViewport(VIEWPORTS.mid, '1024').catch((error) => [{ error: error.message }])
  report.pageStats.adminOverflows1024 = sweep1024
  await guard(report, 'admin:overflow-1024', async () => {
    const bad = sweep1024.filter((item) => item.overflow || item.error)
    if (bad.length) throw new Error('1024 视口存在横向溢出/错误：' + JSON.stringify(bad))
    return '三个页面 1024 均无横向溢出'
  })

  const sweep390 = await sweepViewport(VIEWPORTS.mobile, '390').catch((error) => [{ error: error.message }])
  report.pageStats.adminOverflows390 = sweep390
  await guard(report, 'admin:overflow-390', async () => {
    const bad = sweep390.filter((item) => item.overflow || item.error)
    if (bad.length) throw new Error('390 视口存在横向溢出/错误：' + JSON.stringify(bad))
    return '三个页面 390 均无横向溢出'
  })

  await guard(report, 'admin:eval-chart-390', async () => {
    const entry = sweep390.find((item) => item.view === 'evaluation') || {}
    const plot = entry.chartPlot
    if (!plot) throw new Error('390 视口未采集到 .eval-chart__plot 数据：' + JSON.stringify(entry))
    if (!plot.present) throw new Error('390 视口下未找到趋势图 .eval-chart__plot')
    if (plot.scrollWidth > plot.clientWidth + 1) throw new Error(`趋势图内部横向溢出：scrollWidth=${plot.scrollWidth} > clientWidth=${plot.clientWidth}`)
    const pageOverflow = entry.overflow
    if (pageOverflow) throw new Error('390 视口下评测页整体存在横向溢出：' + JSON.stringify({ scrollWidth: entry.scrollWidth, clientWidth: entry.clientWidth, offenders: (entry.offenders || []).slice(0, 3) }))
    return `趋势图 plot ${plot.scrollWidth}/${plot.clientWidth}（宽 ${plot.width}px），页面无横向溢出`
  })

  /* ---------- global hygiene ---------- */
  await page.setViewport(VIEWPORTS.desktop)
  await guard(report, 'admin:no-console-errors', async () => {
    if (report.consoleErrors.length) throw new Error('控制台错误 ' + report.consoleErrors.length + ' 条：' + JSON.stringify(report.consoleErrors.slice(0, 3)))
    return '0 控制台错误'
  })
  await guard(report, 'admin:no-failed-requests', async () => {
    if (report.failedRequests.length) throw new Error('失败请求 ' + report.failedRequests.length + ' 条：' + JSON.stringify(report.failedRequests.slice(0, 3)))
    return '0 失败请求'
  })
  await guard(report, 'admin:no-raw-i18n-keys', async () => {
    const pageTexts = Object.values(report.pageStats.adminPages || {})
      .map((entry) => (entry?.nav?.text || entry?.text || ''))
      .join('\n') + '\n' + (await page.bodyText())
    const keys = new Set()
    for (const token of pageTexts.split(/[\s"'，。；：,;()（）\[\]{}<>|]+/)) {
      if (/^[a-z][a-z_]*(\.[a-z][a-z_]*){2,}$/.test(token)) keys.add(token)
    }
    if (keys.size) throw new Error('页面出现原始 i18n key：' + JSON.stringify([...keys].slice(0, 10)))
    return '无原始 i18n key'
  })
}

/* ================================================================== register phase (task-13) */

const REGISTER_URL = APP_ORIGIN + '/register/'
const QA_MEMBER = 'qa-member'

/** Auto-accept every JS dialog (window.confirm) and record its message for evidence. */
function watchDialogs(page) {
  const dialogs = []
  page.on('Page.javascriptDialogOpening', (params) => {
    dialogs.push({ type: params.type, message: params.message || '' })
    page.send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  })
  return dialogs
}

/** Spy on navigator.clipboard.writeText without breaking the real write; result lands on window.__copyCapture. */
async function installClipboardSpy(page) {
  return await page.evalFn(() => {
    window.__copyCapture = null
    window.__copyError = ''
    try {
      const descriptor = Object.getOwnPropertyDescriptor(Navigator.prototype, 'clipboard')
      const original = descriptor && typeof descriptor.get === 'function' ? descriptor.get.call(navigator) : navigator.clipboard
      const spy = {
        writeText: async (text) => {
          window.__copyCapture = String(text)
          try {
            if (original && typeof original.writeText === 'function') await original.writeText(text)
          } catch (error) {
            window.__copyError = String(error && error.message ? error.message : error)
          }
        },
      }
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: spy })
      return true
    } catch (error) {
      window.__copyError = 'spy-install-failed: ' + String(error && error.message ? error.message : error)
      return false
    }
  })
}

async function invitesState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const active = document.querySelector('.invite-active')
    const codeNode = active ? active.querySelector('.invite-code') : document.querySelector('.invite-code')
    const metaNode = active ? active.querySelector('.invite-active__meta') : null
    const copyBtn = active ? [...active.querySelectorAll('button')].find((button) => /复制|已复制/.test(norm(button.innerText))) : null
    const rotateBtn = active ? [...active.querySelectorAll('button')].find((button) => /立即轮换/.test(norm(button.innerText))) : null
    const historyRows = [...document.querySelectorAll('.invite-table tbody tr')]
    const rectOf = (node) => {
      if (!node) return null
      const rect = node.getBoundingClientRect()
      return { width: Math.round(rect.width), height: Math.round(rect.height) }
    }
    return {
      hasActive: !!active,
      code: codeNode ? norm(codeNode.innerText) : '',
      codeFont: codeNode ? getComputedStyle(codeNode).fontFamily : '',
      countdown: metaNode ? norm(metaNode.innerText) : '',
      copyButton: copyBtn ? norm(copyBtn.innerText) : '',
      copyRect: rectOf(copyBtn),
      rotateButton: rotateBtn ? norm(rotateBtn.innerText) : '',
      rotateRect: rectOf(rotateBtn),
      historyRows: historyRows.length,
      historyCodes: historyRows.map((row) => norm(row.innerText).split(' ')[0]).filter(Boolean).slice(0, 6),
      bodyText: norm(document.body.innerText).slice(0, 900),
    }
  })
}

async function accessTableState(page, username) {
  return await page.evalFn((wanted) => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const table = document.querySelector('table.access-table')
    const headers = table ? [...table.querySelectorAll('thead th')].map((th) => norm(th.innerText)).filter(Boolean) : []
    const rows = table ? [...table.querySelectorAll('tbody tr')] : []
    const row = rows.find((candidate) => norm(candidate.innerText).includes(wanted)) || null
    const roleChips = row ? [...row.querySelectorAll('.account__roles .tag, td:nth-child(3) .tag')].map((node) => norm(node.innerText)) : []
    const ops = row ? [...row.querySelectorAll('td.access-ops button, td:last-child button')].map((button) => {
      const rect = button.getBoundingClientRect()
      return { text: norm(button.innerText), width: Math.round(rect.width), height: Math.round(rect.height) }
    }) : []
    return {
      headers,
      rows: rows.length,
      found: !!row,
      roleChips,
      ops,
      otherTenant: row ? /其他租户/.test(norm(row.innerText)) : false,
      rowText: row ? norm(row.innerText).slice(0, 240) : '',
      usernames: rows.map((candidate) => norm(candidate.querySelector('.account strong')?.innerText || '')).filter(Boolean).slice(0, 12),
    }
  }, [username])
}

async function registerPageState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const alertNode = document.querySelector('.alert--danger[role="alert"], .alert--danger')
    const submit = document.querySelector('.register-form button[type="submit"], .register-form .btn--primary')
    const rect = submit ? submit.getBoundingClientRect() : null
    return {
      mounted: !!document.querySelector('.register-shell, .register-card'),
      heading: norm((document.querySelector('.register-head__title, h1') || {}).innerText || ''),
      fields: {
        invite: !!document.querySelector('#invite-code'),
        username: !!document.querySelector('#reg-username'),
        password: !!document.querySelector('#reg-password'),
        confirm: !!document.querySelector('#reg-confirm'),
      },
      submitText: submit ? norm(submit.innerText) : '',
      submitRect: rect ? { width: Math.round(rect.width), height: Math.round(rect.height) } : null,
      alert: alertNode ? norm(alertNode.innerText) : '',
      done: !!document.querySelector('.register-done'),
      doneHeading: norm((document.querySelector('.register-done h1') || {}).innerText || ''),
      doneText: norm((document.querySelector('.register-done') || {}).innerText || '').slice(0, 360),
      links: [...document.querySelectorAll('.register-done a')].map((link) => ({ text: norm(link.innerText), href: link.getAttribute('href') || '' })),
      bodyText: norm(document.body.innerText).slice(0, 900),
      styles: {
        sheets: [...document.styleSheets].map((sheet) => sheet.href || '(inline)'),
        bodyFont: getComputedStyle(document.body).fontFamily,
        bodyBg: getComputedStyle(document.body).backgroundColor,
        card: (() => {
          const card = document.querySelector('.register-card')
          if (!card) return null
          const style = getComputedStyle(card)
          const rect = card.getBoundingClientRect()
          return { display: style.display, maxWidth: style.maxWidth, borderRadius: style.borderRadius, padding: style.padding, background: style.backgroundColor, width: Math.round(rect.width) }
        })(),
      },
    }
  })
}

async function keycloakThemeState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const bodyStyle = getComputedStyle(document.body)
    const brandNode = document.querySelector('.login-head__title') || document.querySelector('.login-head__text') || document.querySelector('.login-head')
    const headText = (document.querySelector('.login-head') || document.body || {}).innerText || ''
    const linkNode = document.querySelector('.login-alt__link, a[href*="/register/"]')
    const button = document.querySelector('#kc-login')
    const rect = button ? button.getBoundingClientRect() : null
    return {
      url: location.href,
      title: document.title,
      brand: norm(brandNode ? brandNode.innerText : '') || norm(headText).split(' ').slice(0, 2).join(' '),
      bodyBg: bodyStyle.backgroundColor,
      bodyBgImage: (bodyStyle.backgroundImage || '').slice(0, 140),
      fields: {
        username: !!document.querySelector('#username'),
        password: !!document.querySelector('#password'),
        login: !!document.querySelector('#kc-login'),
      },
      loginText: button ? norm(button.innerText) : '',
      loginRect: rect ? { width: Math.round(rect.width), height: Math.round(rect.height) } : null,
      registerLink: linkNode ? { text: norm(linkNode.innerText), href: linkNode.getAttribute('href') || '' } : null,
      bodyText: norm(document.body.innerText).slice(0, 900),
    }
  })
}

/** Wait for the app's SSO gate card (AuthPanel) and snapshot the CTA. */
async function waitAuthGate(page, label) {
  await page.waitForFn(() => {
    const cta = document.querySelector('.auth-card__cta')
    return !!(cta && /单点登录/.test(cta.innerText || ''))
  }, [], label, { timeout: 35000 })
  await page.settle(400)
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const cta = document.querySelector('.auth-card__cta')
    const rect = cta ? cta.getBoundingClientRect() : null
    return {
      ctaText: cta ? norm(cta.innerText) : '',
      ctaRect: rect ? { width: Math.round(rect.width), height: Math.round(rect.height) } : null,
      bodyText: norm(document.body.innerText).slice(0, 600),
    }
  })
}

async function runRegister(page) {
  const report = page.ctx.report
  report.pageStats.register = {}
  const dialogs = watchDialogs(page)
  const posts = []
  page.on('Network.requestWillBeSent', (params) => {
    const method = String(params.request?.method || '').toUpperCase()
    const url = String(params.request?.url || '')
    if (method !== 'POST') return
    if (/\/api\/v1\/register(\?|$)/.test(url)) posts.push({ kind: 'register', url, at: new Date().toISOString() })
    else if (/\/api\/v1\/invite-codes\/rotate(\?|$)/.test(url)) posts.push({ kind: 'rotate', url, at: new Date().toISOString() })
    else if (/\/api\/v1\/users\/[^/?]+\/(promote|demote)(\?|$)/.test(url)) posts.push({ kind: 'role', url, at: new Date().toISOString() })
  })

  await page.setViewport(VIEWPORTS.desktop)
  page.ctx.activity = 'register:login-theme'
  await page.eval('sessionStorage.clear()').catch(() => null)
  await page.navigate(ASK_URL)

  /* ---------- Keycloak 自定义主题登录页 ---------- */
  await guard(report, 'register:login-page-theme', async () => {
    const gate = await waitAuthGate(page, '登录门禁出现')
    report.pageStats.loginGate = gate
    await page.clickSelector('.auth-card__cta')
    await page.waitForFn(() => location.href.includes(':8180/'), [], '跳转 Keycloak 登录页', { timeout: 30000 })
    await page.waitForFn(() => !!document.querySelector('#kc-login'), [], 'Keycloak 登录表单渲染', { timeout: 30000 })
    await page.settle(500)
    const theme = await keycloakThemeState(page)
    report.pageStats.keycloak = theme
    if (!theme.fields.username || !theme.fields.password || !theme.fields.login) throw new Error('Keycloak 表单字段缺失：' + JSON.stringify(theme.fields))
    if (!/知源/.test(theme.brand)) throw new Error('Keycloak 品牌标题缺少「知源」：' + JSON.stringify(theme.brand))
    if (!/知源/.test(theme.bodyText)) throw new Error('Keycloak 页面缺少品牌文案：' + theme.bodyText.slice(0, 160))
    if (!/rgb\(246, 245, 241\)/i.test(theme.bodyBg)) throw new Error('Keycloak body 背景色与主题不符（期望 rgb(246, 245, 241)）：' + theme.bodyBg)
    if (!theme.registerLink || !/\/register\//.test(theme.registerLink.href)) throw new Error('Keycloak 页脚缺少「注册新账号」链接：' + JSON.stringify(theme.registerLink))
    if (!/登录/.test(theme.loginText)) throw new Error('Keycloak 登录按钮文案异常：' + JSON.stringify(theme.loginText))
    await page.screenshot('login-page-1440')
    return '品牌=' + JSON.stringify(theme.brand) + '，bodyBg=' + theme.bodyBg + '，按钮=' + JSON.stringify(theme.loginText) + ' ' + JSON.stringify(theme.loginRect) + '，注册链接=' + JSON.stringify(theme.registerLink)
  })

  page.ctx.activity = 'register:login-complete'
  await guard(report, 'register:oidc-login', async () => {
    await page.fill('#username', CREDS.username)
    await page.fill('#password', CREDS.password)
    await page.clickSelector('#kc-login')
    await page.waitForFn(() => !!document.querySelector('#question'), [], '登录完成回到 /ask/', { timeout: 45000 })
    await page.settle(500)
    const state = await page.evalFn(() => ({ url: location.href, hasComposer: !!document.querySelector('#question'), bodyText: document.body.innerText.replace(/\s+/g, ' ').slice(0, 300) }))
    report.login = { ok: true, durationMs: null, url: state.url, user: CREDS.username }
    if (!state.hasComposer) throw new Error('登录后未回到问答合成器：' + state.bodyText)
    return '登录后落地 ' + state.url
  })

  /* ---------- #/invites 邀请码 ---------- */
  page.ctx.activity = 'register:invites'
  await page.navigate(ADMIN_URL)
  await page.waitForFn(() => !!(document.body && /文档库/.test(document.body.innerText)), [], '管理壳加载', { timeout: 45000 })
  await page.settle(600)

  let inviteCode = ''
  await guard(report, 'register:invites-nav', async () => {
    await clickAdminNav(page, '邀请码')
    await waitAdminHeading(page, ['邀请码'], '邀请码页面标题')
    await page.waitForFn(() => !!document.querySelector('.invite-active .invite-code'), [], '邀请码面板加载', { timeout: 30000 })
    await page.settle(400)
    const state = await invitesState(page)
    const nav = await adminNavState(page)
    const target = nav.items.find((item) => item.text.includes('邀请码'))
    const hash = await page.eval('location.hash')
    report.pageStats.invites = { code: state.code, countdown: state.countdown, historyRows: state.historyRows, rotateRect: state.rotateRect, copyRect: state.copyRect }
    if (!target) throw new Error('侧栏未找到「邀请码」导航项：' + JSON.stringify(nav.items))
    if (!target.active) throw new Error('「邀请码」导航项未显示 active 状态')
    if (hash !== '#/invites') throw new Error('hash 路由不是 #/invites：' + JSON.stringify(hash))
    return 'h1=邀请码，active=true，hash=' + hash + '，历史行=' + state.historyRows
  })

  await guard(report, 'register:invites-code', async () => {
    const state = await invitesState(page)
    if (!state.hasActive) throw new Error('当前邀请码面板未渲染')
    if (!/^[A-Z0-9]{8}$/.test(state.code)) throw new Error('邀请码格式不符合 ^[A-Z0-9]{8}$：' + JSON.stringify(state.code))
    if (!/mono|Mono|monospace|Consolas|Cascadia|Menlo|ui-monospace/i.test(state.codeFont)) throw new Error('邀请码未使用等宽字体：' + state.codeFont)
    if (!/剩余\s*\d{1,3}:\d{2}/.test(state.countdown)) throw new Error('剩余时间不是 MM:SS 倒计时：' + JSON.stringify(state.countdown))
    inviteCode = state.code
    return 'code=' + state.code + '，font=' + state.codeFont.split(',')[0] + '，' + JSON.stringify(state.countdown) + '，历史行=' + state.historyRows
  })

  await guard(report, 'register:invites-copy', async () => {
    const installed = await installClipboardSpy(page)
    if (!installed) throw new Error('无法安装剪贴板探针（navigator.clipboard 不可覆盖）')
    const before = await invitesState(page)
    const clicked = await page.evalFn(() => {
      const active = document.querySelector('.invite-active')
      const button = active ? [...active.querySelectorAll('button')].find((candidate) => /复制|已复制/.test((candidate.innerText || '').trim())) : null
      if (!button) return false
      button.click()
      return true
    })
    if (!clicked) throw new Error('未找到「复制」按钮')
    await page.waitForFn(() => window.__copyCapture !== null, [], '剪贴板捕获邀请码', { timeout: 6000 }).catch(() => null)
    const captured = await page.evalFn(() => ({ text: window.__copyCapture, error: window.__copyError || '' }))
    const after = await invitesState(page)
    report.pageStats.invitesCopy = { captured: captured.text, error: captured.error, button: after.copyButton }
    if (!captured.text) throw new Error('点击复制后未捕获到写入内容：' + JSON.stringify(captured))
    if (captured.text !== before.code) throw new Error('复制内容与展示邀请码不一致：' + JSON.stringify(captured.text) + ' vs ' + JSON.stringify(before.code))
    if (!/已复制|复制/.test(after.copyButton)) throw new Error('复制按钮缺少反馈状态：' + JSON.stringify(after.copyButton))
    return 'clipboard=' + JSON.stringify(captured.text) + (captured.error ? '（真实写入错误：' + captured.error + '）' : '') + '，按钮=' + JSON.stringify(after.copyButton)
  })

  await guard(report, 'register:invites-rotate', async () => {
    const before = await invitesState(page)
    const dialogCount = dialogs.length
    const rotatePostsBefore = posts.filter((entry) => entry.kind === 'rotate').length
    const clicked = await page.evalFn(() => {
      const active = document.querySelector('.invite-active')
      const button = active ? [...active.querySelectorAll('button')].find((candidate) => /立即轮换/.test((candidate.innerText || '').trim())) : null
      if (!button) return false
      button.click()
      return true
    })
    if (!clicked) throw new Error('未找到「立即轮换」按钮')
    await page.waitForFn((oldCode) => {
      const node = document.querySelector('.invite-active .invite-code')
      const text = node ? node.innerText.trim() : ''
      return text !== oldCode && /^[A-Z0-9]{8}$/.test(text)
    }, [before.code], '邀请码轮换后更新', { timeout: 20000 })
    await page.waitForFn(() => /邀请码已轮换/.test(document.body.innerText), [], '轮换成功提示', { timeout: 8000 }).catch(() => null)
    await page.settle(500)
    const after = await invitesState(page)
    const rotatePosts = posts.filter((entry) => entry.kind === 'rotate').slice(rotatePostsBefore)
    const newDialogs = dialogs.slice(dialogCount)
    report.pageStats.invitesRotate = { before: before.code, after: after.code, historyRows: after.historyRows, dialogs: newDialogs, posts: rotatePosts }
    if (after.code === before.code) throw new Error('轮换后邀请码未变化：' + after.code)
    if (!newDialogs.some((dialog) => /轮换/.test(dialog.message))) throw new Error('未观察到 window.confirm 轮换确认框：' + JSON.stringify(newDialogs))
    if (!rotatePosts.length) throw new Error('未观察到 POST /api/v1/invite-codes/rotate 请求')
    if (after.historyRows < 1) throw new Error('历史邀请码表格为空（rows=' + after.historyRows + '）')
    inviteCode = after.code
    await page.screenshot('admin-invites-1440')
    return '旧码=' + before.code + '，新码=' + after.code + '，历史行=' + after.historyRows + '，confirm=' + JSON.stringify(newDialogs) + '，rotate POST=' + rotatePosts.length + '，toast=邀请码已轮换'
  })

  /* ---------- #/access 操作列 / 擢升 / 降级 ---------- */
  page.ctx.activity = 'register:access'
  await guard(report, 'register:access-nav', async () => {
    await clickAdminNav(page, '用户与权限')
    await waitAdminHeading(page, ['用户与权限'], '用户与权限页面标题')
    await page.waitForFn(() => {
      const table = document.querySelector('table.access-table')
      return !!table && table.querySelectorAll('tbody tr').length > 0
    }, [], '账号表格加载', { timeout: 30000 })
    await page.settle(400)
    const state = await accessTableState(page, QA_MEMBER)
    report.pageStats.access = { headers: state.headers, rows: state.rows, usernames: state.usernames, found: state.found, roleChips: state.roleChips, ops: state.ops, otherTenant: state.otherTenant }
    if (!state.headers.includes('操作')) throw new Error('账号表格缺少「操作」列：' + JSON.stringify(state.headers))
    if (!state.found) {
      warn(report, 'register:access-qa-member', '未找到演示账号 ' + QA_MEMBER + '，跳过擢升/降级断言；当前账号=' + JSON.stringify(state.usernames))
      report.pageStats.access.skip = true
      return '表格 ' + state.rows + ' 行，未找到 ' + QA_MEMBER + '（warn 跳过）'
    }
    return '表格 ' + state.rows + ' 行，' + QA_MEMBER + ' 角色=' + JSON.stringify(state.roleChips) + '，操作=' + JSON.stringify(state.ops)
  })

  if (report.pageStats.access?.skip === true) {
    warn(report, 'register:access-role-change', '跳过 ' + QA_MEMBER + ' 的擢升/降级验证（演示账号不存在）')
  } else {
    page.ctx.activity = 'register:access-promote'
    await guard(report, 'register:access-promote', async () => {
      const before = await accessTableState(page, QA_MEMBER)
      const hasPromote = before.ops.some((op) => /设为管理员/.test(op.text))
      if (!hasPromote) throw new Error('未找到「设为管理员」按钮：' + JSON.stringify(before.ops))
      const dialogCount = dialogs.length
      const rolePostsBefore = posts.filter((entry) => entry.kind === 'role').length
      const clicked = await page.evalFn((wanted) => {
        const rows = [...document.querySelectorAll('table.access-table tbody tr')]
        const row = rows.find((candidate) => (candidate.innerText || '').includes(wanted))
        if (!row) return false
        const button = [...row.querySelectorAll('button')].find((candidate) => /设为管理员/.test((candidate.innerText || '').trim()))
        if (!button) return false
        button.click()
        return true
      }, [QA_MEMBER])
      if (!clicked) throw new Error('无法点击 ' + QA_MEMBER + ' 的「设为管理员」')
      await page.waitForFn((wanted) => {
        const rows = [...document.querySelectorAll('table.access-table tbody tr')]
        const row = rows.find((candidate) => (candidate.innerText || '').includes(wanted))
        if (!row) return false
        const chips = [...row.querySelectorAll('.account__roles .tag, td:nth-child(3) .tag')].map((node) => (node.innerText || '').trim())
        return chips.includes('ADMIN')
      }, [QA_MEMBER], '角色 chip 更新为 ADMIN', { timeout: 20000 })
      await page.settle(400)
      const after = await accessTableState(page, QA_MEMBER)
      const newDialogs = dialogs.slice(dialogCount)
      const rolePosts = posts.filter((entry) => entry.kind === 'role').slice(rolePostsBefore)
      report.pageStats.accessPromote = { before: before.roleChips, after: after.roleChips, dialogs: newDialogs, posts: rolePosts }
      if (!after.roleChips.includes('ADMIN')) throw new Error('擢升后角色 chip 未变为 ADMIN：' + JSON.stringify(after.roleChips))
      if (!rolePosts.some((entry) => /\/users\/[^/?]+\/promote/.test(entry.url))) throw new Error('未观察到 POST /api/v1/users/' + QA_MEMBER + '/promote 请求：' + JSON.stringify(rolePosts))
      if (!newDialogs.some((dialog) => /权限变更|重新登录/.test(dialog.message))) throw new Error('缺少权限变更确认框：' + JSON.stringify(newDialogs))
      if (!/角色已更新/.test(await page.bodyText())) warn(report, 'register:access-promote-toast', '未捕获到「角色已更新」toast（可能已自动消失）')
      await page.screenshot('admin-access-actions-1440')
      return 'chips=' + JSON.stringify(after.roleChips) + '，confirm=' + JSON.stringify(newDialogs) + '，promote POST=' + JSON.stringify(rolePosts.map((entry) => entry.url))
    })

    page.ctx.activity = 'register:access-demote'
    await guard(report, 'register:access-demote', async () => {
      const before = await accessTableState(page, QA_MEMBER)
      const hasDemote = before.ops.some((op) => /设为成员/.test(op.text))
      if (!hasDemote) throw new Error('擢升后未出现「设为成员」按钮：' + JSON.stringify(before.ops))
      const dialogCount = dialogs.length
      const rolePostsBefore = posts.filter((entry) => entry.kind === 'role').length
      const clicked = await page.evalFn((wanted) => {
        const rows = [...document.querySelectorAll('table.access-table tbody tr')]
        const row = rows.find((candidate) => (candidate.innerText || '').includes(wanted))
        if (!row) return false
        const button = [...row.querySelectorAll('button')].find((candidate) => /设为成员/.test((candidate.innerText || '').trim()))
        if (!button) return false
        button.click()
        return true
      }, [QA_MEMBER])
      if (!clicked) throw new Error('无法点击 ' + QA_MEMBER + ' 的「设为成员」')
      await page.waitForFn((wanted) => {
        const rows = [...document.querySelectorAll('table.access-table tbody tr')]
        const row = rows.find((candidate) => (candidate.innerText || '').includes(wanted))
        if (!row) return false
        const chips = [...row.querySelectorAll('.account__roles .tag, td:nth-child(3) .tag')].map((node) => (node.innerText || '').trim())
        return chips.includes('EMPLOYEE') && !chips.includes('ADMIN')
      }, [QA_MEMBER], '角色 chip 回退为 EMPLOYEE', { timeout: 20000 })
      await page.settle(400)
      const after = await accessTableState(page, QA_MEMBER)
      const rolePosts = posts.filter((entry) => entry.kind === 'role').slice(rolePostsBefore)
      report.pageStats.accessDemote = { before: before.roleChips, after: after.roleChips, dialogs: dialogs.slice(dialogCount), posts: rolePosts }
      if (!after.roleChips.includes('EMPLOYEE')) throw new Error('降级后角色 chip 未回到 EMPLOYEE：' + JSON.stringify(after.roleChips))
      if (!rolePosts.some((entry) => /\/users\/[^/?]+\/demote/.test(entry.url))) throw new Error('未观察到 demote POST：' + JSON.stringify(rolePosts))
      return 'chips=' + JSON.stringify(after.roleChips) + '，demote POST=' + JSON.stringify(rolePosts.map((entry) => entry.url))
    })
  }

  /* ---------- /register/ 注册页 ---------- */
  page.ctx.activity = 'register:page'
  await page.setViewport(VIEWPORTS.desktop)
  await page.navigate(REGISTER_URL)
  await page.waitForFn(() => !!document.querySelector('.register-shell, .register-card'), [], '注册页渲染', { timeout: 30000 })
  await page.settle(400)
  const uniqueUser = 'qa-visual-' + Date.now().toString(36)
  report.pageStats.registeredUsername = uniqueUser
  const password = CREDS.password

  await guard(report, 'register:page-render', async () => {
    const state = await registerPageState(page)
    report.pageStats.registerPage = state
    if (!state.mounted) throw new Error('注册页未挂载')
    if (!state.fields.invite || !state.fields.username || !state.fields.password || !state.fields.confirm) throw new Error('注册表单字段缺失：' + JSON.stringify(state.fields))
    if (!/注册/.test(state.submitText)) throw new Error('缺少注册提交按钮：' + JSON.stringify(state.submitText))
    if (!/注册新账号|注册账号/.test(state.bodyText)) throw new Error('注册页标题/文案缺失：' + state.bodyText.slice(0, 200))
    await page.screenshot('register-1440')
    // A registered page that renders raw HTML (no component CSS) looks unstyled: card must be a real card.
    const cardStyles = state.styles && state.styles.card
    if (!cardStyles || cardStyles.borderRadius === '0px' || !/px/.test(cardStyles.maxWidth) || cardStyles.width <= 0) {
      throw new Error('注册页样式未生效（卡片未应用组件样式）：' + JSON.stringify({ card: cardStyles, sheets: state.styles && state.styles.sheets, bodyFont: state.styles && state.styles.bodyFont }))
    }
    return '标题=' + JSON.stringify(state.heading) + '，字段=' + JSON.stringify(state.fields) + '，提交按钮=' + JSON.stringify(state.submitText) + ' ' + JSON.stringify(state.submitRect)
  })

  const submitRegister = async () => {
    await page.evalFn(() => {
      const button = document.querySelector('.register-form button[type="submit"], .register-form .btn--primary')
      if (button) button.click()
      return !!button
    })
    await page.waitForFn(() => {
      const node = document.querySelector('.alert--danger, .register-form .alert')
      return !!(node && (node.innerText || '').trim())
    }, [], '注册校验错误出现', { timeout: 8000 })
    return await page.evalFn(() => {
      const node = document.querySelector('.alert--danger, .register-form .alert')
      return node ? (node.innerText || '').replace(/\s+/g, ' ').trim() : ''
    })
  }

  await guard(report, 'register:client-validation', async () => {
    const errors = []
    const emptyError = await submitRegister()
    errors.push(emptyError)
    if (!/邀请码/.test(emptyError)) throw new Error('空表单未提示邀请码错误：' + JSON.stringify(emptyError))
    await page.fill('#invite-code', 'ZZZZZZZZ')
    const usernameError = await submitRegister()
    errors.push(usernameError)
    if (!/用户名/.test(usernameError)) throw new Error('用户名缺失未提示：' + JSON.stringify(usernameError))
    await page.fill('#reg-username', 'qa-check')
    const passwordError = await submitRegister()
    errors.push(passwordError)
    if (!/密码/.test(passwordError)) throw new Error('密码缺失未提示：' + JSON.stringify(passwordError))
    await page.fill('#reg-password', password)
    await page.fill('#reg-confirm', 'DifferentPass42!')
    const mismatchError = await submitRegister()
    errors.push(mismatchError)
    if (!/不一致/.test(mismatchError)) throw new Error('两次密码不一致未提示：' + JSON.stringify(mismatchError))
    report.pageStats.registerValidation = errors
    return JSON.stringify(errors)
  })

  page.ctx.activity = 'register:server-invalid-code'
  await guard(report, 'register:server-invalid-code', async () => {
    await page.fill('#invite-code', 'AAAAAAAA')
    await page.fill('#reg-username', uniqueUser + '-probe')
    await page.fill('#reg-password', password)
    await page.fill('#reg-confirm', password)
    const postsBefore = posts.filter((entry) => entry.kind === 'register').length
    await page.evalFn(() => {
      const button = document.querySelector('.register-form button[type="submit"], .register-form .btn--primary')
      if (button) button.click()
      return !!button
    })
    await page.waitForFn(() => {
      const node = document.querySelector('.alert--danger')
      return !!(node && /邀请码/.test(node.innerText || ''))
    }, [], '无效邀请码服务端提示', { timeout: 15000 })
    const text = await page.evalFn(() => {
      const node = document.querySelector('.alert--danger')
      return node ? (node.innerText || '').replace(/\s+/g, ' ').trim() : ''
    })
    const attempts = posts.slice(postsBefore)
    report.pageStats.registerServerReject = { alert: text, attempts }
    if (!/邀请码/.test(text)) throw new Error('服务端未返回邀请码无效提示：' + JSON.stringify(text))
    if (!attempts.length) throw new Error('未观察到 POST /api/v1/register 请求')
    return 'alert=' + JSON.stringify(text) + '，POST /api/v1/register=' + attempts.length
  })

  page.ctx.activity = 'register:success'
  await guard(report, 'register:register-success', async () => {
    await page.fill('#invite-code', inviteCode)
    await page.fill('#reg-username', uniqueUser)
    await page.fill('#reg-password', password)
    await page.fill('#reg-confirm', password)
    const postsBefore = posts.filter((entry) => entry.kind === 'register').length
    await page.evalFn(() => {
      const button = document.querySelector('.register-form button[type="submit"], .register-form .btn--primary')
      if (button) button.click()
      return !!button
    })
    await page.waitForFn(() => !!document.querySelector('.register-done'), [], '注册成功卡片', { timeout: 25000 })
    await page.settle(500)
    const state = await registerPageState(page)
    report.pageStats.registerSuccess = state
    const registerPosts = posts.slice(postsBefore)
    if (!/注册成功/.test(state.doneHeading) && !/注册成功/.test(state.doneText)) throw new Error('成功卡片缺少「注册成功」：' + state.doneText.slice(0, 200))
    if (!state.links.some((link) => /去登录/.test(link.text) && /\/admin\//.test(link.href))) throw new Error('缺少「去登录」按钮：' + JSON.stringify(state.links))
    if (!state.links.some((link) => /返回知识问答/.test(link.text) && /\/ask\//.test(link.href))) throw new Error('缺少「返回知识问答」按钮：' + JSON.stringify(state.links))
    if (!registerPosts.length) throw new Error('未观察到注册 POST /api/v1/register')
    await page.screenshot('register-success-1440')
    return '账号=' + JSON.stringify(uniqueUser) + '，卡片=' + state.doneText.slice(0, 120) + '，按钮=' + JSON.stringify(state.links) + '，POST=' + registerPosts.length
  })

  /* ---------- 键盘焦点 / 主 CTA 尺寸 ---------- */
  page.ctx.activity = 'register:a11y'
  await page.navigate(REGISTER_URL)
  await page.waitForFn(() => !!document.querySelector('#invite-code'), [], '注册表单重新渲染', { timeout: 30000 })
  await page.settle(400)

  await guard(report, 'register:focus-visible', async () => {
    await page.evalFn(() => { const input = document.querySelector('#invite-code'); if (input) input.focus(); return true })
    await page.pressTab()
    await page.settle(250)
    const state = await page.evalFn(() => {
      const node = document.activeElement
      if (!node) return null
      const style = getComputedStyle(node)
      return {
        tag: node.tagName,
        id: node.id || '',
        text: (node.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 40),
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
        boxShadow: style.boxShadow,
      }
    })
    report.pageStats.a11yFocus = state
    const visible = state && ((state.outlineStyle === 'solid' && parseFloat(state.outlineWidth) >= 1) || (state.boxShadow && !/none/.test(state.boxShadow)))
    if (!visible) throw new Error('Tab 焦点没有可见轮廓：' + JSON.stringify(state))
    return JSON.stringify(state)
  })

  await guard(report, 'register:primary-cta-44', async () => {
    const state = await registerPageState(page)
    const candidates = [
      { what: '注册按钮', rect: state.submitRect },
      { what: '登录门禁 CTA', rect: report.pageStats.loginGate?.ctaRect },
      { what: 'Keycloak 登录按钮', rect: report.pageStats.keycloak?.loginRect },
      { what: '立即轮换', rect: report.pageStats.invites?.rotateRect },
    ].filter((item) => item.rect)
    report.pageStats.ctaSizes = candidates
    const small = candidates.filter((item) => item.rect.height < 44)
    if (small.length) throw new Error('主 CTA 高度 <44px：' + JSON.stringify(small))
    return JSON.stringify(candidates.map((item) => item.what + ' ' + item.rect.width + 'x' + item.rect.height))
  })

  /* ---------- 390 响应式：注册 / 邀请码 / 用户与权限 ---------- */
  page.ctx.activity = 'register:responsive'
  await guard(report, 'register:overflow-register-390', async () => {
    await page.setViewport(VIEWPORTS.mobile)
    await page.reload()
    await page.waitForFn(() => !!document.querySelector('.register-shell'), [], '390 注册页重新渲染', { timeout: 30000 })
    await page.settle(400)
    const result = await page.overflowCheck('register-page-390')
    report.pageStats.register390 = { innerWidth: result.innerWidth, scrollWidth: result.scrollWidth, clientWidth: result.clientWidth }
    if (result.overflow) throw new Error('注册页 390 横向溢出：scrollWidth=' + result.scrollWidth + ' > clientWidth=' + result.clientWidth + '，offenders=' + JSON.stringify((result.offenders || []).slice(0, 4)))
    return 'innerWidth=' + result.innerWidth + '，scrollWidth=' + result.scrollWidth + ' / clientWidth=' + result.clientWidth
  })

  await guard(report, 'register:overflow-invites-390', async () => {
    await page.navigate(ADMIN_URL)
    await page.waitForFn(() => !!(document.body && /文档库/.test(document.body.innerText)), [], '390 管理壳加载', { timeout: 45000 })
    await page.settle(500)
    await clickAdminNav(page, '邀请码')
    await waitAdminHeading(page, ['邀请码'], '390 邀请码页面标题')
    await page.waitForFn(() => !!document.querySelector('.invite-active .invite-code'), [], '390 邀请码面板', { timeout: 30000 })
    await page.settle(400)
    await page.screenshot('admin-invites-390')
    const result = await page.overflowCheck('invites-390')
    report.pageStats.invites390 = { innerWidth: result.innerWidth, scrollWidth: result.scrollWidth, clientWidth: result.clientWidth }
    if (result.overflow) throw new Error('邀请码页 390 横向溢出：scrollWidth=' + result.scrollWidth + ' > clientWidth=' + result.clientWidth + '，offenders=' + JSON.stringify((result.offenders || []).slice(0, 4)))
    return 'innerWidth=' + result.innerWidth + '，scrollWidth=' + result.scrollWidth + ' / clientWidth=' + result.clientWidth
  })

  await guard(report, 'register:overflow-access-390', async () => {
    await clickAdminNav(page, '用户与权限')
    await waitAdminHeading(page, ['用户与权限'], '390 用户与权限页面标题')
    await page.waitForFn(() => {
      const table = document.querySelector('table.access-table')
      return !!table && table.querySelectorAll('tbody tr').length > 0
    }, [], '390 账号表格', { timeout: 30000 })
    await page.settle(400)
    const result = await page.overflowCheck('access-390')
    report.pageStats.access390 = { innerWidth: result.innerWidth, scrollWidth: result.scrollWidth, clientWidth: result.clientWidth }
    if (result.overflow) throw new Error('用户与权限页 390 横向溢出：scrollWidth=' + result.scrollWidth + ' > clientWidth=' + result.clientWidth + '，offenders=' + JSON.stringify((result.offenders || []).slice(0, 4)))
    return 'innerWidth=' + result.innerWidth + '，scrollWidth=' + result.scrollWidth + ' / clientWidth=' + result.clientWidth
  })

  /* ---------- 最终卫生检查 ---------- */
  await guard(report, 'register:no-console-errors', async () => {
    if (report.consoleErrors.length) throw new Error('存在控制台错误：' + JSON.stringify(report.consoleErrors.slice(0, 3)))
    return 'consoleErrors=0'
  })
  await guard(report, 'register:no-failed-requests', async () => {
    if (report.failedRequests.length) throw new Error('存在失败请求：' + JSON.stringify(report.failedRequests.slice(0, 5)))
    return 'failedRequests=0，ignoredRequests=' + report.ignoredRequests.length
  })
  await guard(report, 'register:no-raw-i18n-keys', async () => {
    const text = await page.bodyText()
    const bad = [...new Set(text.match(/[a-z][a-z_]*(\.[a-z][a-z_]*){2,}/g) || [])]
    if (bad.length) throw new Error('页面出现疑似原始 i18n key：' + JSON.stringify(bad.slice(0, 5)))
    return '未出现原始 i18n key'
  })
}

/* ------------------------------------------------------------------ chat phase (task-15) */
/* /ask/ chat shell: hero composer (#question) → after the first answer a fixed bottom dock
 * (#question-dock) keeps the thread above and appends follow-up Q&A; follow-ups POST
 * {question, history:[last ≤3 answered turns]}. */
const CHAT_QUESTIONS = {
  first: '出差住宿标准是多少？',
  followUp: '它适用于哪些城市？',
  third: '数据分为哪些安全等级？',
}

/** Fetch spy: records every /questions request body + response body into window.__askLog. */
function chatFetchSpy() {
  if (window.__askSpy) return true
  window.__askSpy = true
  window.__askLog = []
  const original = window.fetch.bind(window)
  window.fetch = async function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url) || ''
    let body = null
    try { if (init && init.body) body = JSON.parse(init.body) } catch (error) { body = null }
    const response = await original(input, init)
    try {
      if (body && body.question !== undefined) {
        const copy = response.clone()
        copy.json().then((data) => {
          window.__askLog.push({
            url,
            question: body.question,
            history: Array.isArray(body.history) ? body.history : [],
            status: (data && data.status) || '',
            answerLen: ((data && data.answer) || '').length,
            citations: (data && Array.isArray(data.citations)) ? data.citations.length : 0,
            traceId: (data && data.traceId) || '',
          })
        }).catch(() => {})
      }
    } catch (error) { /* ignore spy bookkeeping errors */ }
    return response
  }
  return true
}

async function chatInstallSpy(page) {
  await page.evalFn(chatFetchSpy)
}

/** Dispatch a real Enter (optionally Shift+Enter) keyboard event through CDP. */
async function chatPressEnter(page, options = {}) {
  const shift = !!options.shift
  const modifiers = shift ? 8 : 0
  const text = shift ? '\n' : '\r'
  const base = { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13, modifiers }
  await page.send('Input.dispatchKeyEvent', { ...base, type: 'keyDown' })
  await page.send('Input.dispatchKeyEvent', { ...base, type: 'char', text, unmodifiedText: text })
  await page.send('Input.dispatchKeyEvent', { ...base, type: 'keyUp' })
}

/** Wait until the Nth thread entry has finished rendering (answer / insufficient / error). */
async function chatWaitTurns(page, expected, timeout = 180000) {
  await page.waitForFn((count) => {
    const text = document.body ? document.body.innerText : ''
    const cards = [...document.querySelectorAll('article.answer')]
    if (cards.length < count) return false
    const last = cards[cards.length - 1]
    const loading = last.querySelector('.answer__loading, .pending-card')
    const body = last.querySelector('.answer__body')
    const empty = last.querySelector('.answer__empty')
    const alert = last.querySelector('.answer__alert')
    const pendingText = /正在生成|正在检索/.test(text)
    return !loading && !pendingText && (!!body || !!empty || !!alert)
  }, [expected], '第 ' + expected + ' 轮回答完成', { timeout })
  await page.waitForFn((count) => (window.__askLog || []).length >= count, [expected], '问答请求日志 ' + expected + ' 条', { timeout: 20000 })
  await page.settle(600)
  await page.waitForFn(() => {
    const root = document.documentElement
    return window.scrollY + window.innerHeight >= root.scrollHeight - 4
  }, [], '页面滚动到底部', { timeout: 6000 }).catch(() => null)
}

/** Snapshot of the chat shell: thread cards, fixed dock geometry and captured request log. */
async function chatThreadState(page) {
  return await page.evalFn(() => {
    const norm = (value) => String(value || '').replace(/\s+/g, ' ').trim()
    const cards = [...document.querySelectorAll('article.answer')]
    const dock = document.querySelector('.ask-dock')
    const dockInput = document.querySelector('#question-dock')
    const dockRect = dock ? dock.getBoundingClientRect() : null
    const inputRect = dockInput ? dockInput.getBoundingClientRect() : null
    const last = cards[cards.length - 1] || null
    const composer = dock ? dock.querySelector('.composer--dock') : null
    const log = window.__askLog || []
    return {
      cards: cards.length,
      userBubbles: cards.map((card) => norm(card.querySelector('.answer__question')?.innerText)).slice(0, 5),
      lastAnswerBottom: last ? Math.round(last.getBoundingClientRect().bottom) : null,
      lastAnswerText: last ? norm(last.querySelector('.answer__body')?.innerText) : '',
      lastCiteChips: last ? last.querySelectorAll('.cite-chip').length : 0,
      lastInsufficient: !!last && /没有找到足够的依据/.test(last.innerText || ''),
      lastAlert: last ? norm(last.querySelector('.answer__alert')?.innerText) : '',
      dock: !!dock,
      dockPosition: dock ? getComputedStyle(dock).position : null,
      dockTop: dockRect ? Math.round(dockRect.top) : null,
      dockBottom: dockRect ? Math.round(dockRect.bottom) : null,
      dockMeta: norm(dock?.querySelector('.ask-dock__meta span')?.innerText),
      newChatLink: !!dock?.querySelector('.ask-dock__meta .link-btn'),
      dockInput: !!dockInput,
      dockInputVisible: !!dockInput && inputRect.width > 40 && inputRect.height > 10,
      dockInputHeight: inputRect ? Math.round(inputRect.height) : 0,
      dockInputWidth: inputRect ? Math.round(inputRect.width) : 0,
      dockComposerHeight: composer ? Math.round(composer.getBoundingClientRect().height) : 0,
      heroComposer: !!document.querySelector('#question'),
      threadNote: norm(document.querySelector('.thread__note')?.innerText),
      chatMode: !!document.querySelector('.ask-page--chat'),
      log: log.map((item) => ({
        question: item.question,
        historyLen: item.history.length,
        historyQuestions: item.history.map((turn) => turn.question),
        status: item.status,
        answerLen: item.answerLen,
        citations: item.citations,
      })),
      location: location.href,
      innerWidth: window.innerWidth,
      bodyText: norm(document.body && document.body.innerText).slice(0, 300),
    }
  })
}

async function runChat(page) {
  const report = page.ctx.report
  page.ctx.activity = 'chat'

  /* ---------- boot + login, empty (hero) state @1440 ---------- */
  await page.setViewport(VIEWPORTS.desktop)
  await page.eval('sessionStorage.clear()').catch(() => null)
  await openApp(page, 'ask')
  await page.waitForFn(() => !!document.querySelector('textarea#question') || !!document.querySelector('#question-dock'), [], '问答页面输入框', { timeout: 30000 })
  await page.evalFn(chatFetchSpy)
  await page.settle(500)

  let heroCtaHeight = 0
  await guard(report, 'chat:empty-hero', async () => {
    const state = await page.evalFn(() => {
      const input = document.querySelector('textarea#question')
      const rect = input ? input.getBoundingClientRect() : null
      const cta = document.querySelector('.composer .btn--primary, .composer button[type="submit"]')
      const ctaRect = cta ? cta.getBoundingClientRect() : null
      return {
        hero: !!input,
        heroVisible: !!input && rect.width > 100 && rect.height > 20,
        heroPlaceholder: input ? input.getAttribute('placeholder') : '',
        dock: !!document.querySelector('.ask-dock'),
        suggestions: [...document.querySelectorAll('.suggestion-chip')].map((item) => (item.innerText || '').trim()).filter(Boolean),
        ctaHeight: ctaRect ? Math.round(ctaRect.height) : 0,
        h1: (document.querySelector('h1')?.innerText || '').trim(),
      }
    })
    report.pageStats.chatEmpty = state
    heroCtaHeight = state.ctaHeight
    if (!state.hero || !state.heroVisible) throw new Error('空态未找到可见的 hero 输入框 textarea#question')
    if (state.dock) throw new Error('空态不应出现底部固定 dock')
    if (state.suggestions.length < 3) throw new Error('空态建议问题少于 3 个: ' + JSON.stringify(state.suggestions))
    return 'hero 可见，建议问题=' + state.suggestions.length + '，提问 CTA 高=' + state.ctaHeight + 'px'
  })

  /* ---------- 1. first question -> answer card with citations ---------- */
  await page.fill('#question', CHAT_QUESTIONS.first)
  await page.waitForFn(() => {
    const button = document.querySelector('.composer .btn--primary, .composer button[type="submit"]')
    return !!button && !button.disabled
  }, [], '首问提交按钮可用', { timeout: 8000 })
  const firstStarted = Date.now()
  await page.clickByText(['提问'], { timeout: 8000 })
  await chatWaitTurns(page, 1)
  const firstState = await chatThreadState(page)
  report.pageStats.chatFirst = { ...firstState, latencyMs: Date.now() - firstStarted }

  await guard(report, 'chat:first-answer', async () => {
    const log = firstState.log[0]
    if (firstState.cards !== 1) throw new Error('首问后回答卡片数=' + firstState.cards + '，期望 1')
    if (!/出差住宿标准/.test(firstState.userBubbles[0] || '')) throw new Error('首问用户气泡缺失: ' + JSON.stringify(firstState.userBubbles))
    if (!(log && log.status === 'ANSWERED')) throw new Error('首问请求状态不是 ANSWERED: ' + JSON.stringify(log || null))
    if (!log.citations) throw new Error('首问返回引用数为 0')
    if (log.historyLen !== 0) throw new Error('首问不应携带 history，实际 historyLen=' + log.historyLen)
    if (firstState.lastInsufficient) throw new Error('首问回答为“没有找到足够的依据”')
    if (firstState.lastAlert) throw new Error('首问回答报错: ' + firstState.lastAlert)
    if (!firstState.lastAnswerText) throw new Error('首问回答文本为空')
    if (!firstState.lastCiteChips) throw new Error('首问回答未渲染引用角标（.cite-chip）')
    return `卡片=1，status=${log.status}，answerLen=${log.answerLen}，citations=${log.citations}，citeChips=${firstState.lastCiteChips}，${Math.round(report.pageStats.chatFirst.latencyMs / 1000)}s`
  })

  /* ---------- 2. chat state: fixed dock, meta, no covering ---------- */
  await guard(report, 'chat:dock-visible', async () => {
    const state = firstState
    const viewportHeight = await page.safeEvalFn(() => window.innerHeight)
    if (!state.chatMode) throw new Error('回答后未进入聊天态（缺少 .ask-page--chat）')
    if (!state.dock) throw new Error('回答后未出现底部固定 dock（.ask-dock）')
    if (state.dockPosition !== 'fixed') throw new Error('dock position=' + state.dockPosition + '，期望 fixed')
    if (Math.abs(state.dockBottom - viewportHeight) > 2) throw new Error('dock 未贴底：bottom=' + state.dockBottom + '，视口高=' + viewportHeight)
    if (!state.dockInput || !state.dockInputVisible) throw new Error('未找到可见的 textarea#question-dock')
    if (!/已进行\s*1\s*轮/.test(state.dockMeta)) throw new Error('dock 元信息未显示“已进行 1 轮”: ' + JSON.stringify(state.dockMeta))
    if (!state.newChatLink) throw new Error('dock 缺少“开始新对话”入口')
    return `dock fixed、贴底（bottom=${state.dockBottom}），meta=${JSON.stringify(state.dockMeta)}，输入框高=${state.dockInputHeight}px`
  })

  await guard(report, 'chat:dock-no-cover', async () => {
    const state = firstState
    if (state.lastAnswerBottom === null || state.dockTop === null) throw new Error('无法测量最后一条回答/dock 位置')
    if (state.lastAnswerBottom > state.dockTop + 1) throw new Error('dock 遮挡最后一条回答：answerBottom=' + state.lastAnswerBottom + ' > dockTop=' + state.dockTop)
    return `lastAnswerBottom=${state.lastAnswerBottom} <= dockTop=${state.dockTop}（间距 ${state.dockTop - state.lastAnswerBottom}px）`
  })

  /* ---------- 3. follow-up via Enter: history + second answer in same thread ---------- */
  await guard(report, 'chat:followup-history', async () => {
    await page.evalFn(() => {
      const input = document.querySelector('#question-dock')
      if (input) { input.click(); input.focus() }
      return !!input
    })
    await page.fill('#question-dock', CHAT_QUESTIONS.followUp)
    const before = await chatThreadState(page)
    if (!before.dockInput) throw new Error('写入追问前未找到 dock 输入框')
    const started = Date.now()
    await chatPressEnter(page)
    await chatWaitTurns(page, 2)
    const state = await chatThreadState(page)
    report.pageStats.chatFollowUp = { ...state, latencyMs: Date.now() - started }
    const log = state.log[1]
    if (state.cards !== 2) throw new Error('追问后回答卡片数=' + state.cards + '，期望 2')
    if (!/它适用于哪些城市/.test(state.userBubbles[1] || '')) throw new Error('追问用户气泡缺失: ' + JSON.stringify(state.userBubbles))
    if (!/已进行\s*2\s*轮/.test(state.dockMeta)) throw new Error('dock 元信息未显示“已进行 2 轮”: ' + JSON.stringify(state.dockMeta))
    if (!(log && log.status === 'ANSWERED')) throw new Error('追问状态不是 ANSWERED（history 未生效）: ' + JSON.stringify(log || null))
    if (!log.citations) throw new Error('追问回答没有引用: ' + JSON.stringify(log))
    if (!log.historyLen || log.historyQuestions[0] !== CHAT_QUESTIONS.first) throw new Error('追问未携带首问 history: ' + JSON.stringify(log))
    if (state.lastInsufficient) throw new Error('追问回答为“没有找到足够的依据”（history 未生效）')
    if (state.lastAnswerText.length < 10) throw new Error('追问回答文本过短: ' + JSON.stringify(state.lastAnswerText))
    return `同线程第 2 轮：status=${log.status}，citations=${log.citations}，history=${JSON.stringify(log.historyQuestions)}`
  })

  await page.settle(400)
  await page.screenshot('ask-chat-1440')

  /* ---------- 4. focus / Shift+Enter / Enter / new conversation ---------- */
  await guard(report, 'chat:dock-focus', async () => {
    const focused = await page.evalFn(() => {
      const input = document.querySelector('#question-dock')
      if (!input) return { ok: false, id: '', tag: '' }
      input.click()
      input.focus()
      return { ok: document.activeElement === input, id: document.activeElement ? document.activeElement.id : '', tag: document.activeElement ? document.activeElement.tagName : '' }
    })
    if (!focused.ok) throw new Error('点击 dock 输入框后未获得焦点: ' + JSON.stringify(focused))
    await page.fill('#question-dock', 'Shift+Enter 换行测试')
    await page.screenshot('ask-chat-focus-1440')
    return 'dock 输入框可点击并聚焦（activeElement=#' + focused.id + '）'
  })

  await guard(report, 'chat:shift-enter-newline', async () => {
    const before = await chatThreadState(page)
    const readState = () => page.evalFn(() => {
      const input = document.querySelector('#question-dock')
      return { value: input ? input.value : '', cards: document.querySelectorAll('article.answer').length, logLen: (window.__askLog || []).length }
    })
    await chatPressEnter(page, { shift: true })
    await page.settle(600)
    let after = await readState()
    let method = 'cdp-key'
    // headless 下 CDP 合成键不会执行 textarea 原生的 InsertLineBreak 默认行为（非应用缺陷）；
    // 用 Chrome 对 Shift+Enter 采用的同一编辑命令兜底，再派发 input 同步 v-model。
    if (!after.value.includes('\n')) {
      await page.evalFn(() => {
        const input = document.querySelector('#question-dock')
        if (!input) return false
        input.focus()
        document.execCommand('insertLineBreak')
        input.dispatchEvent(new Event('input', { bubbles: true }))
        return input.value
      })
      await page.settle(400)
      after = await readState()
      method = 'execCommand-insertLineBreak'
    }
    if (!after.value.includes('\n')) {
      await page.send('Input.insertText', { text: '\n' })
      await page.evalFn(() => {
        const input = document.querySelector('#question-dock')
        if (input) input.dispatchEvent(new Event('input', { bubbles: true }))
      })
      await page.settle(400)
      after = await readState()
      method = 'Input.insertText'
    }
    if (!after.value.includes('\n')) throw new Error('Shift+Enter 未插入换行，textarea 值=' + JSON.stringify(after.value))
    if (after.cards !== before.cards) throw new Error('Shift+Enter 触发了发送：卡片 ' + before.cards + '→' + after.cards)
    if (after.logLen !== before.log.length) throw new Error('Shift+Enter 触发了请求：log ' + before.log.length + '→' + after.logLen)
    await page.fill('#question-dock', '')
    return 'Shift+Enter 插入换行且未发送（卡片数保持 ' + after.cards + '，换行方式 ' + method + '）'
  })

  await guard(report, 'chat:enter-sends', async () => {
    await page.fill('#question-dock', CHAT_QUESTIONS.third)
    await chatPressEnter(page)
    await chatWaitTurns(page, 3)
    const state = await chatThreadState(page)
    report.pageStats.chatThird = state
    const log = state.log[2]
    if (state.cards !== 3) throw new Error('Enter 发送后卡片数=' + state.cards + '，期望 3')
    if (!(log && log.status === 'ANSWERED')) throw new Error('第三问状态不是 ANSWERED: ' + JSON.stringify(log || null))
    if (log.historyLen < 2) throw new Error('第三问 history 少于 2 轮: ' + log.historyLen)
    if (!/已进行\s*3\s*轮/.test(state.dockMeta)) throw new Error('dock 元信息未显示“已进行 3 轮”: ' + JSON.stringify(state.dockMeta))
    return `Enter 发送成功，共 3 轮，第三问 history=${log.historyLen}，citations=${log.citations}`
  })

  const dockCtaHeight = (await page.safeEvalFn(() => {
    const button = document.querySelector('.ask-dock .composer .btn--primary')
    return button ? Math.round(button.getBoundingClientRect().height) : 0
  })) || 0
  report.pageStats.ctaHeights = { hero: heroCtaHeight, dock: dockCtaHeight }

  await guard(report, 'chat:new-conversation', async () => {
    const logBefore = (await chatThreadState(page)).log.length
    await page.clickByText(['开始新对话'], { timeout: 8000 })
    await page.waitForFn(() => !!document.querySelector('textarea#question') && !document.querySelector('.ask-dock'), [], '回到空态', { timeout: 10000 })
    await page.settle(500)
    const state = await page.evalFn(() => ({
      cards: document.querySelectorAll('article.answer').length,
      hero: !!document.querySelector('textarea#question'),
      dock: !!document.querySelector('.ask-dock'),
      hints: !!document.querySelector('.ask-hints'),
      logLen: (window.__askLog || []).length,
    }))
    if (!state.hero) throw new Error('“开始新对话”后未回到 hero 输入框')
    if (state.dock) throw new Error('“开始新对话”后底部 dock 未移除')
    if (state.cards !== 0) throw new Error('“开始新对话”后线程未清空: cards=' + state.cards)
    if (!state.hints) throw new Error('“开始新对话”后建议问题区未恢复')
    if (state.logLen !== logBefore) throw new Error('“开始新对话”不应发起请求: log=' + logBefore + '→' + state.logLen)
    return '线程清空、dock 移除、hero 恢复，无新增请求'
  })

  /* ---------- 5. 390 sweep: dock visible/clickable, no horizontal overflow ---------- */
  await page.setViewport(VIEWPORTS.mobile)
  await page.reload()
  await waitForShell(page, 'ask')
  await page.waitForFn(() => !!document.querySelector('textarea#question'), [], '390 问答输入框', { timeout: 30000 })
  await page.evalFn(chatFetchSpy)
  await page.settle(400)

  await guard(report, 'chat:mobile-dock', async () => {
    await page.fill('#question', CHAT_QUESTIONS.first)
    await page.waitForFn(() => {
      const button = document.querySelector('.composer .btn--primary, .composer button[type="submit"]')
      return !!button && !button.disabled
    }, [], '390 提问按钮可用', { timeout: 8000 })
    await page.clickByText(['提问'], { timeout: 8000 })
    await chatWaitTurns(page, 1)
    const state = await chatThreadState(page)
    report.pageStats.chat390 = state
    if (!state.dock || !state.dockInputVisible) throw new Error('390 未出现可见 dock 输入框: ' + JSON.stringify({ dock: state.dock, visible: state.dockInputVisible, h: state.dockInputHeight }))
    if (state.dockInputHeight <= 0) throw new Error('390 dock 输入框高度为 0')
    const hitTarget = Math.max(state.dockInputHeight, state.dockComposerHeight)
    if (hitTarget < 44) throw new Error('390 dock 输入区命中区域小于 44px: textarea=' + state.dockInputHeight + '，composer=' + state.dockComposerHeight)
    const focusable = await page.evalFn(() => {
      const input = document.querySelector('#question-dock')
      if (!input) return false
      input.click()
      input.focus()
      return document.activeElement === input
    })
    if (!focusable) throw new Error('390 dock 输入框无法点击聚焦')
    await page.settle(500)
    await page.screenshot('ask-chat-dock-390')
    return `390 dock 可见可点：textarea=${state.dockInputHeight}px，composer=${state.dockComposerHeight}px`
  })

  await guard(report, 'chat:mobile-overflow', async () => {
    const result = await page.overflowCheck('chat-390')
    const cardOffenders = await page.evalFn(() => {
      const width = document.documentElement.clientWidth
      return [...document.querySelectorAll('article.answer, .answer__card')]
        .map((card) => card.getBoundingClientRect())
        .filter((rect) => rect.right > width + 1)
        .map((rect) => Math.round(rect.right))
    })
    report.pageStats.chatMobileOverflow = {
      innerWidth: result.innerWidth,
      scrollWidth: result.scrollWidth,
      clientWidth: result.clientWidth,
      cardOffenders,
    }
    if (result.overflow) throw new Error('390 聊天页横向溢出：scrollWidth=' + result.scrollWidth + ' > clientWidth=' + result.clientWidth + '，offenders=' + JSON.stringify((result.offenders || []).slice(0, 4)))
    if (cardOffenders.length) throw new Error('390 回答卡片超出视口：' + JSON.stringify(cardOffenders))
    return '无横向溢出（innerWidth=' + result.innerWidth + '，scrollWidth=' + result.scrollWidth + '/' + result.clientWidth + '）'
  })

  /* ---------- 6. hygiene + primary CTA ---------- */
  await guard(report, 'chat:no-console-errors', async () => {
    if (report.consoleErrors.length) throw new Error('存在控制台错误：' + JSON.stringify(report.consoleErrors.slice(0, 3)))
    return 'consoleErrors=0'
  })
  await guard(report, 'chat:no-failed-requests', async () => {
    if (report.failedRequests.length) throw new Error('存在失败请求：' + JSON.stringify(report.failedRequests.slice(0, 5)))
    return 'failedRequests=0，ignoredRequests=' + report.ignoredRequests.length
  })
  await guard(report, 'chat:no-raw-i18n-keys', async () => {
    const text = await page.bodyText()
    const bad = [...new Set(text.match(/[a-z][a-z_]*(\.[a-z][a-z_]*){2,}/g) || [])]
    if (bad.length) throw new Error('页面出现疑似原始 i18n key：' + JSON.stringify(bad.slice(0, 5)))
    return '未出现原始 i18n key'
  })
  await guard(report, 'chat:primary-cta-44', async () => {
    const heights = report.pageStats.ctaHeights || {}
    const issues = []
    if (heights.hero && heights.hero < 44) issues.push('hero 提问 ' + heights.hero + 'px')
    if (heights.dock && heights.dock < 44) issues.push('dock 发送 ' + heights.dock + 'px')
    if (issues.length) throw new Error('主 CTA 高度小于 44px: ' + issues.join('，'))
    return 'hero 提问=' + heights.hero + 'px，dock 发送=' + heights.dock + 'px'
  })
}

/* ------------------------------------------------------------------ main */
async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    console.log('Usage: node scripts/ui_capture.mjs [--phase=baseline|new|admin|register|chat] [--port=9333] [--keep-profile]')
    return 0
  }
  if (!CHROME || !(await pathExists(CHROME))) throw new Error('Chrome 不存在: ' + CHROME)

  await mkdir(SHOTS, { recursive: true })
  const report = createReport(args.phase, args)
  const profileDir = path.join(tmpdir(), `rag-ui-capture-${process.pid}-${Date.now()}`)
  await mkdir(profileDir, { recursive: true })
  const logFile = path.join(ARTIFACTS, `chrome-stderr-${args.phase}.log`)
  const port = args.port || await getFreePort()
  console.log(`[run] phase=${args.phase} chrome-port=${port} profile=${profileDir}`)
  console.log(`[run] chrome=${CHROME}`)

  let chromeState = null
  let cdp = null
  let page = null
  const ctx = { report, viewport: VIEWPORTS.desktop.name, activity: 'startup' }
  let fatal = null
  const startedAt = Date.now()

  try {
    chromeState = await launchChrome(port, profileDir, logFile)
    const version = await (await fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(5000) })).json()
    report.chrome = { port, browser: version.Browser, protocolVersion: version['Protocol-Version'], userDataDir: profileDir, logFile: 'artifacts/chrome-stderr-' + args.phase + '.log' }
    info(report, 'chrome:launched', `${version.Browser} on port ${port}`)

    cdp = new CDP(version.webSocketDebuggerUrl)
    await cdp.connect()
    info(report, 'cdp:connected', 'browser WebSocket connected')

    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' })
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true })
    page = new PageSession(cdp, sessionId, ctx)
    wireCollection(page, report)
    await page.enable()
    info(report, 'cdp:session', `target=${targetId.slice(0, 8)} session=${sessionId.slice(0, 8)}`)

    if (args.phase === 'baseline') await runBaseline(page)
    else if (args.phase === 'admin') await runAdmin(page)
    else if (args.phase === 'register') await runRegister(page)
    else if (args.phase === 'chat') await runChat(page)
    else await runNew(page)

    // Snapshot the authenticated page text for evidence.
    report.pageText = (await page.bodyText()).replace(/\s+/g, ' ').slice(0, 1200)
  } catch (error) {
    fatal = error
    report.fatal = { message: error.message, stack: (error.stack || '').split('\n').slice(0, 8).join('\n') }
    console.error('[fatal]', error.message)
    if (page) {
      try { ctx.activity = 'failure'; await page.screenshot(`failure-${args.phase}`) } catch { /* ignore */ }
      try { await writeFile(path.join(ARTIFACTS, `failure-${args.phase}.txt`), await page.bodyText(), 'utf8') } catch { /* ignore */ }
    }
  } finally {
    if (page) page.dispose()
    await killChrome(chromeState, cdp)
    if (!args.keepProfile) {
      try { await rm(profileDir, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 }) } catch { /* ignore */ }
    }
    report.finishedAt = new Date().toISOString()
    report.durationMs = Date.now() - startedAt
    report.summary = summarize(report)
    const reportFile = args.phase === 'baseline'
      ? path.join(ARTIFACTS, 'ui_visual_report.json')
      : (args.phase === 'admin'
        ? path.join(ARTIFACTS, 'ui_visual_report-admin.json')
        : (args.phase === 'register'
          ? path.join(ARTIFACTS, 'ui_visual_report-register.json')
          : (args.phase === 'chat'
            ? path.join(ARTIFACTS, 'ui_visual_report-chat.json')
            : path.join(ARTIFACTS, 'ui_visual_report-new.json'))))
    if (args.phase === 'new') {
      // Preserve the baseline report as -baseline.json before overwriting the canonical path.
      const baselineFile = path.join(ARTIFACTS, 'ui_visual_report.json')
      if (await pathExists(baselineFile)) {
        const existing = JSON.parse(await readFile(baselineFile, 'utf8'))
        if (existing.phase === 'baseline') {
          await writeFile(path.join(ARTIFACTS, 'ui_visual_report-baseline.json'), JSON.stringify(existing, null, 2), 'utf8')
        }
        await writeFile(baselineFile, JSON.stringify(report, null, 2), 'utf8')
      }
    }
    await writeFile(reportFile, JSON.stringify(report, null, 2), 'utf8')
    console.log(`[report] ${path.relative(ROOT, reportFile)}`)
    printSummary(report)
  }

  if (fatal) return 1
  if (args.phase !== 'baseline' && report.checks.some((check) => check.status === 'fail')) return 1
  return 0
}

async function pathExists(file) {
  try { await readFile(file); return true } catch { try { await mkdir(path.dirname(file), { recursive: true }); return false } catch { return false } }
}

function wireCollection(page, report) {
  page.on('Runtime.exceptionThrown', (params) => {
    const details = params.exceptionDetails || {}
    const description = details.exception?.description || details.text || 'unknown exception'
    report.consoleErrors.push({
      kind: 'exception',
      phase: page.ctx.activity,
      text: String(description).split('\n').slice(0, 3).join('\n'),
      url: details.url || '',
      line: details.lineNumber,
      at: new Date().toISOString(),
    })
  })
  page.on('Runtime.consoleAPICalled', (params) => {
    if (params.type !== 'error') return
    const text = (params.args || []).map((arg) => arg.value ?? arg.description ?? arg.type).join(' ')
    report.consoleErrors.push({ kind: 'console.error', phase: page.ctx.activity, text: String(text).slice(0, 500), url: '', line: null, at: new Date().toISOString() })
  })
  page.on('Log.entryAdded', (params) => {
    const entry = params.entry || {}
    if (entry.level !== 'error') return
    const url = entry.url || ''
    const text = String(entry.text || '')
    // The negative registration test produces a documented 400; keep it as evidence, not a console failure.
    if (/\/api\/v1\/register(\?|$)/.test(url) && /(Failed to load resource|status of 4\d\d|400)/i.test(text)) {
      report.ignoredRequests.push({ kind: 'console.error', phase: page.ctx.activity, status: 400, method: 'POST', url, text: text.slice(0, 300), reason: 'expected 400 from negative invite-code registration test' })
      return
    }
    // Keycloak login page (custom theme) has no favicon.ico; a 404 for it is benign static-asset noise.
    if (url.startsWith(KEYCLOAK_ORIGIN) && /\/favicon\.ico(\?|$)/.test(url) && /(Failed to load resource|status of 404)/i.test(text)) {
      report.ignoredRequests.push({ kind: 'log.error', phase: page.ctx.activity, status: 404, method: 'GET', url, text: text.slice(0, 300), reason: 'Keycloak favicon 404 (custom theme has no favicon asset; benign)' })
      return
    }
    report.consoleErrors.push({
      kind: 'log.error',
      phase: page.ctx.activity,
      text: String(entry.text || '').slice(0, 500),
      url: entry.url || '',
      line: entry.lineNumber,
      at: new Date().toISOString(),
    })
  })
  const requests = new Map()
  page.on('Network.requestWillBeSent', (params) => {
    if (requests.size > 500) requests.clear()
    requests.set(params.requestId, { method: params.request?.method, url: params.request?.url })
  })
  page.on('Network.responseReceived', (params) => {
    const response = params.response || {}
    if (!(response.status >= 400)) return
    const request = requests.get(params.requestId) || {}
    const url = response.url || request.url || ''
    const entry = {
      phase: page.ctx.activity,
      status: response.status,
      statusText: response.statusText,
      method: request.method || '',
      type: params.type || '',
      url,
      mimeType: response.mimeType || '',
      at: new Date().toISOString(),
    }
    if (/\/favicon\.ico(\?|$)/.test(url)) {
      report.ignoredRequests.push({ ...entry, reason: 'favicon request (gateway denyAll → 401; benign, no favicon asset)' })
      return
    }
    // Negative registration test intentionally posts a bad invite code; the 400 is expected evidence.
    if (/\/api\/v1\/register(\?|$)/.test(url) && response.status === 400 && (entry.method || 'POST') === 'POST') {
      report.ignoredRequests.push({ ...entry, reason: 'expected 400 from negative invite-code registration test' })
      return
    }
    if (url.startsWith(KEYCLOAK_ORIGIN) && /\/resources\/|\/favicon\.ico/.test(url) && response.status === 404) {
      report.ignoredRequests.push({ ...entry, reason: 'keycloak static asset 404' })
      return
    }
    report.failedRequests.push(entry)
  })
}

function summarize(report) {
  const count = (list) => list.length
  return {
    steps: { pass: report.steps.filter((step) => step.status === 'pass').length, fail: report.steps.filter((step) => step.status === 'fail').length, warn: report.steps.filter((step) => step.status === 'warn').length, info: report.steps.filter((step) => step.status === 'info').length },
    checks: report.checks.length ? { pass: report.checks.filter((check) => check.status === 'pass').length, fail: report.checks.filter((check) => check.status === 'fail').length } : undefined,
    screenshots: count(report.screenshots),
    screenshotFiles: report.screenshots.map((shot) => shot.file),
    consoleErrors: count(report.consoleErrors),
    failedRequests: count(report.failedRequests),
    ignoredRequests: count(report.ignoredRequests),
    overflowViolations: count(report.overflows.filter((item) => item.overflow)),
    loginOk: !!report.login?.ok,
    fatal: report.fatal ? report.fatal.message : null,
  }
}

function printSummary(report) {
  const summary = report.summary
  console.log('\n===== ' + report.phase.toUpperCase() + ' SUMMARY =====')
  console.log('duration=' + Math.round((report.durationMs || 0) / 1000) + 's login=' + (summary.loginOk ? 'ok' : 'NO') + ' screenshots=' + summary.screenshots)
  if (summary.checks) console.log('checks: ' + summary.checks.pass + ' pass / ' + summary.checks.fail + ' fail')
  console.log('consoleErrors=' + summary.consoleErrors + ' failedRequests=' + summary.failedRequests + ' ignoredRequests=' + summary.ignoredRequests + ' overflowViolations=' + summary.overflowViolations)
  if (report.fatal) console.log('FATAL: ' + report.fatal.message)
  const failedChecks = report.checks.filter((check) => check.status === 'fail')
  for (const check of failedChecks) console.log('  FAIL check: ' + check.name + ' — ' + check.detail)
  const overflow = report.overflows.filter((item) => item.overflow)
  for (const item of overflow) console.log('  OVERFLOW: ' + item.label + ' scrollWidth=' + item.scrollWidth + ' clientWidth=' + item.clientWidth)
  const failedRequests = report.failedRequests.slice(0, 15)
  for (const request of failedRequests) console.log('  HTTP ' + request.status + ' ' + request.method + ' ' + request.url + ' [' + request.phase + ']')
  const consoleErrors = report.consoleErrors.slice(0, 15)
  for (const error of consoleErrors) console.log('  CONSOLE[' + error.kind + '] ' + error.phase + ': ' + error.text.replace(/\n/g, ' ').slice(0, 200))
}

main().then((code) => { process.exitCode = code }).catch((error) => {
  console.error('[unhandled]', error.stack || error.message)
  process.exitCode = 1
})
