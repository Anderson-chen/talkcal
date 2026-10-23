// 開發時 /api 要轉給哪一個後端（vite.config.ts 的 proxy 每個請求都問一次）。#109
//
// 好幾個 worktree 同時 bootRun 時，後端的埠不固定：第一個拿 8090，之後的由系統挑（見 backend/build.gradle 的 bootRun）。
// 實際拿到的埠由 Spring Boot 寫進「這個資料夾」的 backend/build/application.port，所以讀自己這份就只會接到自己的後端。
//
// 為什麼還要看 pid：app 被硬砍（關掉終端機、工作管理員結束）時沒機會刪檔，留下舊的埠號。
// 那一號之後可能被別的 worktree 的後端佔走 —— 照舊檔去轉，就會默默接到別人的後端、看到別人的資料庫。
// 所以 pid 已經不在，就當作沒有後端，清楚說「找不到」。
import { readFileSync } from 'node:fs'

export type BackendTarget = { url: string } | { error: string }

/**
 * @param buildDir backend/build/ 的路徑
 * @param env      TALKCAL_API 有設就直接用它（例如想接到 deploy/ 那台 18090，或同時比對新舊兩版後端）
 * @param isAlive  某個 pid 還在不在（測試時換掉）
 */
export function backendTarget(
  buildDir: string,
  env: Record<string, string | undefined> = process.env,
  isAlive: (pid: number) => boolean = processAlive,
): BackendTarget {
  if (env.TALKCAL_API) return { url: env.TALKCAL_API }
  const port = readNumber(`${buildDir}/application.port`)
  if (port === null) {
    return { error: '找不到後端：這個資料夾的 bootRun 沒在跑（沒有 backend/build/application.port）。到 backend/ 跑 ./gradlew bootRun' }
  }
  const pid = readNumber(`${buildDir}/application.pid`)
  if (pid === null || !isAlive(pid)) {
    return { error: `找不到後端：這個資料夾上次的 bootRun（埠 ${port}）已經結束了。到 backend/ 重新 ./gradlew bootRun` }
  }
  // 用 127.0.0.1 不用 localhost：Node 可能把 localhost 解析成 ::1，跟 Tomcat 聽的不一定是同一個
  return { url: `http://127.0.0.1:${port}` }
}

function readNumber(path: string): number | null {
  try {
    const value = Number(readFileSync(path, 'utf8').trim())
    return Number.isInteger(value) && value > 0 ? value : null
  } catch {
    return null
  }
}

// signal 0 不會真的送訊號，只檢查程序在不在（Windows 也支援）。EPERM = 在，只是不是我們能管的
function processAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (e) {
    return (e as { code?: string }).code === 'EPERM'
  }
}
