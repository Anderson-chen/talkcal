// #43 失敗畫面演練：把每一種失敗真的造出來，桌面、手機各截一張。
//
// 前提：compose.yaml 那組 talkcal43-* 已經起來（web 18443、app 18843），模型是 llm-proxy 裡的假模型（fake-model.conf）。
// 跑：cd web && node ../docs/drills/2026-10-19-failure-ux/shoot.mjs before|after [截圖資料夾]
//   before = 改之前的前端（現況），after = 這個 PR 的前端。兩次用同一組步驟，截圖檔名只差前綴
//   截圖預設存到系統暫存資料夾的 talkcal-failure-ux/，不進 repo：圖用 gh --attach 傳進 PR（.claude/rules/pr.md）
//
// 故障都打在容器上（見 compose.yaml），真正的模型和共用的 talkcal-* 都不動。
// 帳號每次現註冊一個用完即丟的（隨機名稱、隨機密碼，不寫下來）；登入直接打 app 的埠，不經過 nginx 的登入限流。
import { execSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(join(here, '../../../web/package.json'))
const { chromium, devices, request } = require('@playwright/test')

const phase = process.argv[2]
if (!['before', 'after'].includes(phase)) throw new Error('用法：shoot.mjs before|after [截圖資料夾]')
const shots = process.argv[3] ?? join(tmpdir(), 'talkcal-failure-ux')
mkdirSync(shots, { recursive: true })

const WEB = 'http://127.0.0.1:18443'
const APP = 'http://127.0.0.1:18843'
// 用 label 找輸入框：倒數期間 placeholder 會換成「N 秒後可以再問」（after）
const INPUT = '輸入訊息'

const log = (...args) => console.log(new Date().toISOString().slice(11, 19), ...args)
const docker = (cmd) => { log('docker', cmd); execSync(`docker ${cmd}`, { stdio: 'ignore' }) }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ── 帳號：直接對 app 註冊、登入，cookie 給兩個瀏覽器共用（cookie 不分埠，18843 拿到的 18443 也認）──
async function login() {
  const api = await request.newContext({ baseURL: APP })
  await api.get('/api/auth/me')
  const xsrf = async () => (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value
  const username = 'u' + randomBytes(4).toString('hex')
  const password = randomBytes(12).toString('hex')
  let res = await api.post('/api/auth/register', { data: { username, password }, headers: { 'X-XSRF-TOKEN': await xsrf() } })
  if (res.status() !== 201) throw new Error(`註冊失敗 ${res.status()}`)
  res = await api.post('/api/auth/login', { form: { username, password }, headers: { 'X-XSRF-TOKEN': await xsrf() } })
  if (res.status() !== 204) throw new Error(`登入失敗 ${res.status()}`)
  await api.get('/api/auth/me')
  return api
}

/** 直接對 app（不經過 nginx）問助理：用來湊熔斷需要的失敗次數、佔住 bulkhead。 */
async function askDirect(api, message = '明天有什麼行程？', timeout = 180_000) {
  const token = (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value
  const res = await api.post('/api/calendar/assistant', { data: { message }, headers: { 'X-XSRF-TOKEN': token }, timeout })
  const body = await res.json().catch(() => null)
  return { status: res.status(), code: body?.code ?? null }
}

async function main() {
  let api = await login()
  const browser = await chromium.launch()
  const contexts = {}
  const pages = {}
  const openPages = async () => {
    const state = await api.storageState()
    for (const [name, opts] of [['desktop', { viewport: { width: 1280, height: 800 } }], ['mobile', devices['Pixel 7']]]) {
      await contexts[name]?.close()
      contexts[name] = await browser.newContext({ ...opts, locale: 'zh-TW', timezoneId: 'Asia/Taipei', storageState: state })
      const page = await contexts[name].newPage()
      await page.goto(WEB)
      if (name === 'mobile') await page.getByRole('button', { name: '開啟 AI 助理' }).click()
      await page.getByLabel(INPUT).waitFor()
      pages[name] = page
    }
  }
  await openPages()

  const shot = async (name, scenario) => {
    const file = join(shots, `${phase}-${scenario}-${name}.png`)
    await pages[name].screenshot({ path: file })
    log('截圖', `${phase}-${scenario}-${name}.png`)
  }

  // 對助理說一句；wait=true 就等到「思考中」消失（回覆或錯誤出現）
  const ask = async (name, text, wait = true) => {
    const page = pages[name]
    const isAsk = (r) => r.url().endsWith('/api/calendar/assistant') && r.method() === 'POST'
    // 有回應就記狀態碼；網路斷了沒有回應，等的是 requestfailed
    const res = Promise.race([
      page.waitForResponse((r) => isAsk(r.request()), { timeout: 200_000 }).then((r) => ({ status: r.status() })),
      page.waitForEvent('requestfailed', { predicate: isAsk, timeout: 200_000 }).then(() => ({ status: 'no response' })),
    ]).catch(() => ({ status: 'timeout' }))
    await page.getByLabel(INPUT).fill(text)
    await page.getByLabel(INPUT).press('Enter')
    if (!wait) return res
    const result = await res
    await page.locator('.bubble.typing').waitFor({ state: 'detached', timeout: 200_000 })
    await sleep(300)
    log(name, `「${text}」→`, result.status)
    return result
  }

  // 1. 正常：不該出現任何錯誤畫面
  for (const name of ['desktop', 'mobile']) {
    await ask(name, '明天有什麼行程？')
    await shot(name, '1-normal')
    const alerts = await pages[name].getByRole('alert').count()
    log(name, '正常回覆時的 alert 數：', alerts)
  }

  // 2. 模型斷線：這一次叫了模型、失敗 → 502 ASSISTANT_FAILED
  docker('stop talkcal43-llm-proxy')
  for (const name of ['desktop', 'mobile']) {
    await ask(name, '明天下午三點和 Amy 開會')
    await shot(name, '2-model-failed')
  }

  // 3. 熔斷打開 → 503 MODEL_UNAVAILABLE（Retry-After 30）。直接打 app 把失敗湊到打開為止
  for (let i = 0; i < 10; i++) {
    const r = await askDirect(api)
    log('湊失敗', r.status, r.code)
    if (r.code === 'MODEL_UNAVAILABLE') break
  }
  for (const name of ['desktop', 'mobile']) {
    await ask(name, '明天下午三點和 Amy 開會')
    await shot(name, '3-circuit-open')
  }
  if (phase === 'after') {
    // 倒數期間：送出鈕不能按、打的字還在。打一句新的，確認送不出去
    await pages.desktop.getByLabel(INPUT).fill('後天早上看牙醫')
    await shot('desktop', '3-circuit-open-typing')
    const disabled = await pages.desktop.getByRole('button', { name: '送出' }).isDisabled()
    log('倒數中送出鈕 disabled =', disabled)
    await sleep(31_000)
    await shot('desktop', '3-circuit-open-countdown-done')
  }

  // 模型開回來，等熔斷關回來（半開時放 2 個試探成功才關）
  docker('start talkcal43-llm-proxy')
  for (let i = 0; i < 30; i++) {
    const r = await askDirect(api)
    log('等熔斷關回來', r.status, r.code)
    if (r.status === 200) break
    await sleep(5_000)
  }
  await askDirect(api)

  // 4. 卡住＋滿載：bulkhead 縮成 1。一邊卡在模型上（截「等很久」），另一邊就是 503 MODEL_BUSY
  for (const [slow, busy] of [['desktop', 'mobile'], ['mobile', 'desktop']]) {
    docker('pause talkcal43-llm-proxy')
    const pending = ask(slow, '這週哪天下午有空？', false)
    await sleep(16_000)
    await shot(slow, '4-slow')
    await ask(busy, '明天有什麼行程？')
    await shot(busy, '4-busy')
    docker('unpause talkcal43-llm-proxy')
    log(slow, '卡住那句 →', (await pending).status)
    await pages[slow].locator('.bubble.typing').waitFor({ state: 'detached', timeout: 200_000 })
    // 忙碌的那邊在倒數 5 秒（after），等它倒數完再進下一步
    await sleep(6_000)
  }

  // 5. 網路斷了：請求根本沒送出去
  for (const name of ['desktop', 'mobile']) {
    await contexts[name].setOffline(true)
    await ask(name, '明天有什麼行程？')
    await shot(name, '5-offline')
    await contexts[name].setOffline(false)
  }

  // 6. app 停掉：nginx 自己回錯誤（不是 JSON、沒有 code）
  docker('stop talkcal43-app')
  for (const name of ['desktop', 'mobile']) {
    await ask(name, '明天有什麼行程？')
    await shot(name, '6-app-down')
  }
  // 行事曆本身也讀不到：換到下個月
  // app 停掉時 nginx 要先問 Docker 的 DNS、問不到才回 502，大約 5 秒：等 8 秒再截
  await pages.desktop.getByRole('main').getByRole('button', { name: '下個月' }).click()
  await sleep(8_000)
  await shot('desktop', '6-app-down-calendar')
  // 這時候重新整理頁面
  for (const name of ['desktop', 'mobile']) {
    await pages[name].reload()
    // 先截剛重新整理（還在等「我是誰」）的樣子，再截 nginx 回錯誤之後的樣子
    await sleep(2_000)
    await shot(name, '6-app-down-reload-waiting')
    await sleep(6_000)
    await shot(name, '6-app-down-reload')
  }
  docker('start talkcal43-app')
  for (let i = 0; i < 60; i++) {
    const ok = await fetch(`${WEB}/api/auth/providers`).then((r) => r.ok).catch(() => false)
    if (ok) break
    await sleep(2_000)
  }
  // session 在 app 的記憶體裡，重啟後要重新登入
  await api.dispose()
  api = await login()
  await openPages()

  // 7. 問太快：nginx 的限流（每個 IP 每分鐘 10 次）回 429 + Retry-After 60。用空白訊息灌（app 回 400，不叫模型）
  const flood = await request.newContext({ baseURL: WEB, storageState: await api.storageState() })
  const token = (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value
  for (let i = 0; i < 20; i++) await flood.post('/api/calendar/assistant', { data: { message: ' ' }, headers: { 'X-XSRF-TOKEN': token } })
  for (const name of ['desktop', 'mobile']) {
    await ask(name, '明天有什麼行程？')
    await shot(name, '7-rate-limited')
  }

  await browser.close()
  await api.dispose()
  await flood.dispose()
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
